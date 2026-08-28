// Package wsclient 管理 SuSuMonitor Agent 与后端的 WebSocket 连接。
//
// 连接地址为 ws://<backend>/ws/agent。首帧发送 agent.authenticate，
// 鉴权成功后可发送 heartbeat 和 metrics.report。
package wsclient

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"sync/atomic"
	"time"
)

// AgentMessage 是 WebSocket 通用消息结构，与后端协议文档一致。
//
// 字段说明见 docs-SuMon/Protocol-SuMon/websocket-protocol.md。
type AgentMessage struct {
	Type      string          `json:"type"`
	MessageID string          `json:"message_id"`
	Timestamp string          `json:"timestamp"`
	Payload   json.RawMessage `json:"payload"`
}

// AuthPayload 是 agent.authenticate 首帧的 payload。
type AuthPayload struct {
	ServerID int64  `json:"server_id"`
	Token    string `json:"token"`
}

// HeartbeatPayload 是 heartbeat 消息的 payload。
//
// 投递遥测字段仅在提供时出现；未注入统计提供器时序列化为空对象，保持
// 与旧协议兼容。
type HeartbeatPayload struct {
	PendingCount      *int    `json:"pending_count,omitempty"`
	PendingBytes      *int    `json:"pending_bytes,omitempty"`
	OldestCollectedAt *string `json:"oldest_collected_at,omitempty"`
	DropCount         *uint64 `json:"drop_count,omitempty"`
	DeadLetterCount   *int    `json:"dead_letter_count,omitempty"`
	DeadLetterBytes   *int    `json:"dead_letter_bytes,omitempty"`
}

// NewHeartbeatPayloadWithDeliveryStats 将投递遥测快照转换为心跳载荷。
//
// oldestCollectedAt 为空时省略，其余计数始终携带（0 表示无积压/无丢弃）。
func NewHeartbeatPayloadWithDeliveryStats(pendingCount, pendingBytes int, oldestCollectedAt string,
	dropCount uint64, deadLetterCount, deadLetterBytes int) HeartbeatPayload {
	payload := HeartbeatPayload{
		PendingCount:    &pendingCount,
		PendingBytes:    &pendingBytes,
		DropCount:       &dropCount,
		DeadLetterCount: &deadLetterCount,
		DeadLetterBytes: &deadLetterBytes,
	}
	if oldestCollectedAt != "" {
		payload.OldestCollectedAt = &oldestCollectedAt
	}
	return payload
}

// MetricsNack 是服务端对永久无效 metrics.report 的拒绝载荷。
//
// 服务端仅对可关联且永久无效的指标返回 metrics.nack（reason 为
// invalid_metrics_payload / stale_collected_at / server_not_found），
// Agent 收到后将队首移入本地 dead-letter 而不重试。
type MetricsNack struct {
	ServerID int64  `json:"server_id"`
	Code     int    `json:"code"`
	Reason   string `json:"reason"`
	Message  string `json:"message"`
}

// MetricsPayload 是 metrics.report 消息的指标载荷，与后端固定宽表一一对应。
//
// 指针类型字段表示可空；Windows 上 temperature 和 load_avg 通常为 nil。
type MetricsPayload struct {
	ServerID      int64    `json:"server_id"`
	CollectedAt   string   `json:"collected_at"`
	CPUPercent    *float64 `json:"cpu_percent"`
	MemoryPercent *float64 `json:"memory_percent"`
	MemoryUsed    *uint64  `json:"memory_used"`
	MemoryTotal   *uint64  `json:"memory_total"`
	DiskPercent   *float64 `json:"disk_percent"`
	DiskUsed      *uint64  `json:"disk_used"`
	DiskTotal     *uint64  `json:"disk_total"`
	NetRx         *uint64  `json:"net_rx"`
	NetTx         *uint64  `json:"net_tx"`
	Temperature   *float64 `json:"temperature"`
	LoadAvg       *float64 `json:"load_avg"`
}

// TerminalOpenPayload 是服务端要求 Agent 创建本地 PTY 的固定参数。
type TerminalOpenPayload struct {
	ServerID  int64  `json:"server_id,omitempty"`
	SessionID string `json:"session_id"`
	Cols      uint16 `json:"cols"`
	Rows      uint16 `json:"rows"`
}

// TerminalInputPayload 是 Base64 编码的 PTY 输入字节。
type TerminalInputPayload struct {
	ServerID  int64  `json:"server_id,omitempty"`
	SessionID string `json:"session_id"`
	Data      string `json:"data"`
}

// TerminalResizePayload 表示 PTY 新尺寸。
type TerminalResizePayload struct {
	ServerID  int64  `json:"server_id,omitempty"`
	SessionID string `json:"session_id"`
	Cols      uint16 `json:"cols"`
	Rows      uint16 `json:"rows"`
}

// TerminalClosePayload 要求关闭指定 PTY。
type TerminalClosePayload struct {
	ServerID  int64  `json:"server_id,omitempty"`
	SessionID string `json:"session_id"`
	Reason    string `json:"reason"`
}

// TerminalOpenedPayload 确认 PTY 已创建。
type TerminalOpenedPayload struct {
	ServerID  int64  `json:"server_id"`
	SessionID string `json:"session_id"`
	Shell     string `json:"shell"`
}

// TerminalOutputPayload 是 Base64 编码的 PTY 输出字节。
type TerminalOutputPayload struct {
	ServerID  int64  `json:"server_id"`
	SessionID string `json:"session_id"`
	Data      string `json:"data"`
}

// TerminalClosedPayload 表示 PTY 已退出或被关闭。
type TerminalClosedPayload struct {
	ServerID  int64  `json:"server_id"`
	SessionID string `json:"session_id"`
	Reason    string `json:"reason"`
	ExitCode  *int   `json:"exit_code,omitempty"`
}

// TerminalErrorPayload 只返回稳定错误码和安全错误消息。
type TerminalErrorPayload struct {
	ServerID  int64  `json:"server_id"`
	SessionID string `json:"session_id,omitempty"`
	Code      int    `json:"code"`
	Message   string `json:"message"`
}

// newMessage 构造通用 WebSocket 消息，自动生成 message_id 和 timestamp。
//
// timestamp 使用 UTC ISO-8601（RFC3339Nano），与后端 OffsetDateTime.toString() 兼容。
func newMessage(msgType string, payload interface{}) AgentMessage {
	payloadBytes, _ := json.Marshal(payload)
	return AgentMessage{
		Type:      msgType,
		MessageID: newUUID(),
		Timestamp: time.Now().UTC().Format(time.RFC3339Nano),
		Payload:   payloadBytes,
	}
}

// NewMessage 创建需要由外部模块发送的 Agent 协议消息。
func NewMessage(msgType string, payload interface{}) AgentMessage {
	return newMessage(msgType, payload)
}

// NewMessageWithID 创建关联既有请求的 Agent 协议消息。
func NewMessageWithID(msgType string, messageID string, payload interface{}) AgentMessage {
	message := newMessage(msgType, payload)
	message.MessageID = messageID
	return message
}

// newAuthMessage 构造 agent.authenticate 首帧消息。
func newAuthMessage(serverID int64, token string) AgentMessage {
	return newMessage("agent.authenticate", AuthPayload{
		ServerID: serverID,
		Token:    token,
	})
}

// newHeartbeatMessage 构造 heartbeat 消息。
func newHeartbeatMessage() AgentMessage {
	return newMessage("heartbeat", HeartbeatPayload{})
}

var uuidFallbackCounter uint64

// newUUID 生成 UUID v4 字符串，用于 message_id。
//
// 优先使用 crypto/rand；随机源异常时使用进程内计数器和当前时间构造
// 确定性的 16 字节值，并仍设置 RFC 4122 版本/变体位，避免生成违反协议的 ID。
func newUUID() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return formatUUID(newUUIDFallbackBytes(b))
	}
	return formatUUID(b)
}

// newUUIDFallbackBytes 在 crypto/rand 失败时用时间戳+原子计数器派生 16 字节，
// 保证 fallback 输出仍是合法 UUID v4 形状。
func newUUIDFallbackBytes(dst []byte) []byte {
	seed := fmt.Sprintf("%d:%d", time.Now().UnixNano(), atomic.AddUint64(&uuidFallbackCounter, 1))
	digest := sha256.Sum256([]byte(seed))
	copy(dst, digest[:16])
	return dst
}

// formatUUID 设置 RFC 4122 版本位（第 7 字节高 4 位为 0x4）和变体位（第 9 字节高 2 位为 0b10），
// 并格式化为标准 8-4-4-4-12 字符串。
func formatUUID(b []byte) string {
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:])
}
