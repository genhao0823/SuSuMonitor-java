// Package wsclient 的 client.go 实现 WebSocket 客户端。
//
// 客户端连接 ws://<backend>/ws/agent，首帧发送 agent.authenticate，
// 鉴权成功后每 heartbeatInterval 发送 heartbeat，并持续接收服务端消息。
// 连接断开后按指数退避重连。
package wsclient

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"math/rand"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"
)

const (
	// wsPath 是 Agent WebSocket 端点路径，与后端 AgentWebSocketConfig 一致。
	wsPath = "/ws/agent"
	// authTimeout 是首帧认证超时，与后端 AgentWebSocketHandler 的 10 秒清理一致。
	authTimeout = 10 * time.Second
	// writeTimeout 是单次 WebSocket 写操作超时。
	writeTimeout = 5 * time.Second
	// readTimeout 是接收循环的读超时，略大于后端 90 秒离线判定。
	readTimeout = 95 * time.Second
)

// Client 管理 WebSocket 连接、鉴权、心跳和重连。
type Client struct {
	backendURL           string
	serverID             int64
	token                string
	logger               *slog.Logger
	heartbeatInterval    time.Duration
	reconnectInitial     time.Duration
	reconnectMax         time.Duration
	connectionMu         sync.RWMutex
	connection           *websocket.Conn
	authenticated        bool
	messageHandler         func(context.Context, AgentMessage)
	metricsAckHandler      func(string)
	metricsNackHandler     func(string, MetricsNack)
	heartbeatStatsProvider func() HeartbeatPayload
	authenticatedHandler   func()
	disconnectHandler      func()
}

// SetMessageHandler 设置认证后服务端消息处理器。
//
// 调用方必须在 Run 前设置，运行期间不允许更换处理器。
func (c *Client) SetMessageHandler(handler func(context.Context, AgentMessage)) {
	c.messageHandler = handler
}

// SetMetricsAckHandler sets the handler for a server metrics.ack frame.
//
// The callback receives the correlated metrics.report message ID and must be
// registered before Run starts.
func (c *Client) SetMetricsAckHandler(handler func(string)) {
	c.metricsAckHandler = handler
}

// SetMetricsNackHandler sets the handler for a server metrics.nack frame.
//
// The callback receives the correlated metrics.report message ID and the
// permanent rejection payload, and must be registered before Run starts.
func (c *Client) SetMetricsNackHandler(handler func(string, MetricsNack)) {
	c.metricsNackHandler = handler
}

// SetHeartbeatStatsProvider sets a provider for delivery telemetry included in
// each heartbeat payload. The provider must be registered before Run starts
// and return quickly; nil payloads keep the heartbeat payload empty.
func (c *Client) SetHeartbeatStatsProvider(provider func() HeartbeatPayload) {
	c.heartbeatStatsProvider = provider
}

// SetAuthenticatedHandler sets a callback invoked after an authenticated
// connection is published, so callers can safely send durable queued messages.
// The callback must be registered before Run starts and return quickly.
func (c *Client) SetAuthenticatedHandler(handler func()) {
	c.authenticatedHandler = handler
}

// SetDisconnectHandler 设置已认证连接异常断开时的清理回调。
//
// 调用方必须在 Run 前设置，运行期间不允许更换处理器。
// 契约：回调在 Run 的 goroutine 上同步调用，必须快速返回且幂等，
// 不得 panic（panic 会被捕获并记录）；正常关停（context 取消）时不触发，
// 关停清理由调用方自己负责。
func (c *Client) SetDisconnectHandler(handler func()) {
	c.disconnectHandler = handler
}

// NewClient 创建 WebSocket 客户端。
//
// backendURL 形如 ws://localhost:18080，不含路径。
// token 是 admin 预发放的 Agent Token，不写入日志。
// heartbeatInterval 是心跳间隔，与后端 90 秒离线判定配合（建议 30 秒）。
// reconnectInitial 是重连初始间隔，reconnectMax 是重连最大间隔。
func NewClient(backendURL string, serverID int64, token string, logger *slog.Logger,
	heartbeatInterval, reconnectInitial, reconnectMax time.Duration) *Client {
	return &Client{
		backendURL:        backendURL,
		serverID:          serverID,
		token:             token,
		logger:            logger,
		heartbeatInterval: heartbeatInterval,
		reconnectInitial:  reconnectInitial,
		reconnectMax:      reconnectMax,
	}
}

// errAuthInvalidatedCode 是服务端认证失效错误码（如 token 被 rotate/revoke）。
const errAuthInvalidatedCode = 40100

// errAuthInvalidated 表示认证已被服务端永久拒绝，重连无法恢复。
var errAuthInvalidated = errors.New("agent auth invalidated")

// Run 启动连接、首帧鉴权和消息循环，阻塞直到 context 取消或出现终态错误。
//
// 状态机：Disconnected → Connecting → Authenticating → Connected → Reconnecting。
// 连接或鉴权失败后按带 jitter 的指数退避重连（reconnectInitial → *2 → 上限 reconnectMax）；
// 每次认证成功后重置退避为初始值。
//
// 终态错误：context 取消、非法间隔配置、服务端返回 40100 认证失效。
// 40100 表示 token 已被 rotate/revoke，重连无法恢复，由调用方决定退出。
func (c *Client) Run(ctx context.Context) error {
	if c.heartbeatInterval <= 0 {
		return fmt.Errorf("run: heartbeat interval must be positive, got %s", c.heartbeatInterval)
	}
	if c.reconnectInitial <= 0 {
		return fmt.Errorf("run: reconnect initial interval must be positive, got %s", c.reconnectInitial)
	}
	if c.reconnectMax < c.reconnectInitial {
		return fmt.Errorf("run: reconnect max (%s) must be >= initial (%s)", c.reconnectMax, c.reconnectInitial)
	}

	backoff := c.reconnectInitial
	for {
		if err := ctx.Err(); err != nil {
			return err
		}

		conn, err := c.connectAndAuthenticate(ctx)
		if err != nil {
			c.logger.Warn("connect or authenticate failed, reconnecting",
				"error", err, "backoff", backoff)
			if err := waitBackoff(ctx, backoff); err != nil {
				return err
			}
			backoff = min(backoff*2, c.reconnectMax)
			continue
		}

		// 认证成功，重置退避，保证后续连接丢失从初始间隔重新开始。
		backoff = c.reconnectInitial
		c.logger.Info("agent authenticated", "server_id", c.serverID)
		c.connectionMu.Lock()
		c.connection = conn
		c.authenticated = true
		c.connectionMu.Unlock()
		c.notifyAuthenticated()

		err = c.runLoops(ctx, conn)
		c.connectionMu.Lock()
		if c.connection == conn {
			c.connection = nil
			c.authenticated = false
		}
		c.connectionMu.Unlock()

		// 正常关停由调用方负责清理，这里不触发 disconnectHandler。
		if ctxErr := ctx.Err(); ctxErr != nil {
			return ctxErr
		}
		if errors.Is(err, errAuthInvalidated) {
			return err
		}
		c.notifyDisconnect()
		c.logger.Warn("authenticated connection lost, reconnecting", "error", err, "backoff", backoff)
		if err := waitBackoff(ctx, backoff); err != nil {
			return err
		}
		backoff = min(backoff*2, c.reconnectMax)
	}
}

// notifyAuthenticated synchronously invokes the registered connection-ready
// callback after the authenticated connection is visible to SendMessage.
func (c *Client) notifyAuthenticated() {
	if c.authenticatedHandler == nil {
		return
	}
	defer func() {
		if r := recover(); r != nil {
			c.logger.Error("authenticated handler panicked", "panic", r)
		}
	}()
	c.authenticatedHandler()
}

// notifyDisconnect 同步调用已注册的断连回调并防御 panic。
//
// 仅在已认证连接异常丢失（而非正常关停）时由 Run 调用。
func (c *Client) notifyDisconnect() {
	if c.disconnectHandler == nil {
		return
	}
	defer func() {
		if r := recover(); r != nil {
			c.logger.Error("disconnect handler panicked", "panic", r)
		}
	}()
	c.disconnectHandler()
}

// waitBackoff 在重连前等待，并允许调用方 context 取消等待。
//
// 等待时长采用 equal jitter，实际范围 [backoff/2, backoff]，避免多 Agent
// 同时断线后同步重连形成惊群。backoff 非正数时按 1ms 兜底。
func waitBackoff(ctx context.Context, backoff time.Duration) error {
	if backoff <= 0 {
		backoff = time.Millisecond
	}
	half := backoff / 2
	wait := half + time.Duration(rand.Int63n(int64(half)+1))
	timer := time.NewTimer(wait)
	defer timer.Stop()
	select {
	case <-timer.C:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

// parseServerError 解析顶层 error 帧；非 error 帧返回 ok=false。
//
// 协议要求客户端按 code 分支而不是解析 message 文本。
func parseServerError(msg AgentMessage) (code int, message string, ok bool) {
	if msg.Type != "error" {
		return 0, "", false
	}
	var payload struct {
		Code    int    `json:"code"`
		Message string `json:"message"`
	}
	if err := json.Unmarshal(msg.Payload, &payload); err != nil {
		return 0, "", true
	}
	return payload.Code, payload.Message, true
}

// connectAndAuthenticate 建立 WebSocket 连接并完成首帧鉴权。
//
// 流程：Dial → 发 agent.authenticate → 读 agent.authenticated → 返回 conn。
// 任何步骤失败都关闭连接并返回错误，由 Run 决定重连。
func (c *Client) connectAndAuthenticate(ctx context.Context) (*websocket.Conn, error) {
	url := c.backendURL + wsPath

	// Dial 超时与首帧认证超时一致。
	dialCtx, cancel := context.WithTimeout(ctx, authTimeout)
	defer cancel()

	conn, _, err := websocket.Dial(dialCtx, url, nil)
	if err != nil {
		return nil, fmt.Errorf("dial %s: %w", url, err)
	}

	// 发送首帧 agent.authenticate。
	authMsg := newAuthMessage(c.serverID, c.token)
	writeCtx, cancelWrite := context.WithTimeout(ctx, writeTimeout)
	defer cancelWrite()

	if err := wsjson.Write(writeCtx, conn, authMsg); err != nil {
		conn.CloseNow()
		return nil, fmt.Errorf("write authenticate: %w", err)
	}

	// 等待 agent.authenticated 响应。
	readCtx, cancelRead := context.WithTimeout(ctx, authTimeout)
	defer cancelRead()

	var resp AgentMessage
	if err := wsjson.Read(readCtx, conn, &resp); err != nil {
		conn.CloseNow()
		return nil, fmt.Errorf("read authenticated: %w", err)
	}

	if resp.Type != "agent.authenticated" {
		conn.CloseNow()
		return nil, fmt.Errorf("unexpected auth response type: %s", resp.Type)
	}

	return conn, nil
}

// runLoops 运行心跳和接收循环，任一出错即返回。
//
// 心跳 goroutine 每 heartbeatInterval 发送 heartbeat。
// 接收 goroutine 持续读取服务端消息（heartbeat.ack、error 等）。
func (c *Client) runLoops(ctx context.Context, conn *websocket.Conn) error {
	loopCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	heartbeatTicker := time.NewTicker(c.heartbeatInterval)
	defer heartbeatTicker.Stop()

	errCh := make(chan error, 2)
	var loops sync.WaitGroup
	loops.Add(2)

	// 心跳 goroutine。
	go func() {
		defer loops.Done()
		ticker := heartbeatTicker
		for {
			select {
			case <-loopCtx.Done():
				errCh <- loopCtx.Err()
				return
			case <-ticker.C:
				if err := c.sendHeartbeat(loopCtx, conn); err != nil {
					errCh <- fmt.Errorf("heartbeat: %w", err)
					return
				}
			}
		}
	}()

	// 接收 goroutine。
	go func() {
		defer loops.Done()
		for {
			if err := loopCtx.Err(); err != nil {
				errCh <- err
				return
			}
			var msg AgentMessage
			readCtx, cancelRead := context.WithTimeout(loopCtx, readTimeout)
			err := wsjson.Read(readCtx, conn, &msg)
			cancelRead()
			if err != nil {
				errCh <- fmt.Errorf("read: %w", err)
				return
			}
			if code, message, ok := parseServerError(msg); ok {
				if code == errAuthInvalidatedCode {
					c.logger.Error("agent auth invalidated, stopping", "code", code, "message", message)
					errCh <- errAuthInvalidated
					return
				}
				c.logger.Warn("server error", "code", code, "message", message)
				continue
			}
			c.handleMessage(loopCtx, msg)
		}
	}()

	err := <-errCh
	cancel()
	conn.CloseNow()
	loops.Wait()
	return err
}

// sendHeartbeat 发送 heartbeat 消息，可选携带投递遥测统计。
func (c *Client) sendHeartbeat(ctx context.Context, conn *websocket.Conn) error {
	payload := HeartbeatPayload{}
	if c.heartbeatStatsProvider != nil {
		payload = c.heartbeatStatsProvider()
	}
	msg := newMessage("heartbeat", payload)
	writeCtx, cancel := context.WithTimeout(ctx, writeTimeout)
	defer cancel()
	if err := wsjson.Write(writeCtx, conn, msg); err != nil {
		return err
	}
	c.logger.Debug("heartbeat sent", "message_id", msg.MessageID)
	return nil
}

// heartbeat.ack → Debug 日志；metrics.ack → 专用确认处理器；metrics.nack →
// 专用拒绝处理器；default → 交给业务处理器。
// 顶层 error 帧由 runLoops 的接收 goroutine 先行拦截，不进入这里。
func (c *Client) handleMessage(ctx context.Context, msg AgentMessage) {
	switch msg.Type {
	case "heartbeat.ack":
		c.logger.Debug("heartbeat ack received", "message_id", msg.MessageID)
	case "metrics.ack":
		if msg.MessageID == "" {
			c.logger.Warn("ignored metrics acknowledgement without message ID")
			return
		}
		if c.metricsAckHandler != nil {
			c.metricsAckHandler(msg.MessageID)
			return
		}
		c.logger.Debug("metrics acknowledgement received", "message_id", msg.MessageID)
	case "metrics.nack":
		if msg.MessageID == "" {
			c.logger.Warn("ignored metrics rejection without message ID")
			return
		}
		var nack MetricsNack
		if err := json.Unmarshal(msg.Payload, &nack); err != nil {
			c.logger.Warn("ignored malformed metrics rejection", "message_id", msg.MessageID, "error", err)
			return
		}
		if c.metricsNackHandler != nil {
			c.metricsNackHandler(msg.MessageID, nack)
			return
		}
		c.logger.Debug("metrics rejection received", "message_id", msg.MessageID, "reason", nack.Reason)
	default:
		if c.messageHandler != nil {
			c.messageHandler(ctx, msg)
			return
		}
		c.logger.Debug("received message", "type", msg.Type)
	}
}

// SendMessage 通过当前已认证连接发送任意 Agent 协议消息。
func (c *Client) SendMessage(ctx context.Context, message AgentMessage) error {
	c.connectionMu.RLock()
	conn := c.connection
	authenticated := c.authenticated
	c.connectionMu.RUnlock()
	if conn == nil || !authenticated {
		return fmt.Errorf("send message: agent is not authenticated")
	}
	if err := wsjson.Write(ctx, conn, message); err != nil {
		return fmt.Errorf("write message: %w", err)
	}
	return nil
}

// SendMetrics 通过当前已认证连接发送 metrics.report 消息。
//
// 锁只保护连接和认证状态快照，不覆盖网络写入。断连可与发送并发发生，
// 调用方必须将写入错误视为本次在线 best-effort 上报失败。
func (c *Client) SendMetrics(payload MetricsPayload) error {
	c.connectionMu.RLock()
	conn := c.connection
	authenticated := c.authenticated
	c.connectionMu.RUnlock()
	if conn == nil || !authenticated {
		return fmt.Errorf("send metrics: agent is not authenticated")
	}

	msg := newMessage("metrics.report", payload)
	writeCtx, cancel := context.WithTimeout(context.Background(), writeTimeout)
	defer cancel()
	if err := wsjson.Write(writeCtx, conn, msg); err != nil {
		return fmt.Errorf("write metrics: %w", err)
	}
	c.logger.Debug("metrics sent", "message_id", msg.MessageID)
	return nil
}
