package wsclient

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"
)

const testTimeout = 2 * time.Second

func TestConnectAndAuthenticateSendsAuthMessage(t *testing.T) {
	authMessages := make(chan AgentMessage, 1)
	server := newTestServer(t, func(ctx context.Context, conn *websocket.Conn) {
		var message AgentMessage
		if err := wsjson.Read(ctx, conn, &message); err != nil {
			t.Errorf("read authenticate: %v", err)
			return
		}
		authMessages <- message
		if err := wsjson.Write(ctx, conn, newMessage("agent.authenticated", map[string]any{"server_id": 42})); err != nil {
			t.Errorf("write authenticated: %v", err)
		}
	})
	defer server.Close()

	client := newTestClient(server.URL, 42, 100*time.Millisecond)
	conn, err := client.connectAndAuthenticate(context.Background())
	if err != nil {
		t.Fatalf("connectAndAuthenticate() error = %v", err)
	}
	defer conn.CloseNow()

	message := receiveMessage(t, authMessages)
	if message.Type != "agent.authenticate" {
		t.Fatalf("auth message type = %q", message.Type)
	}
	var payload AuthPayload
	if err := json.Unmarshal(message.Payload, &payload); err != nil {
		t.Fatalf("unmarshal auth payload: %v", err)
	}
	if payload.ServerID != 42 || payload.Token != "test-token" {
		t.Fatalf("auth payload = %+v", payload)
	}
}

func TestConnectAndAuthenticateRejectsUnexpectedResponse(t *testing.T) {
	server := newTestServer(t, func(ctx context.Context, conn *websocket.Conn) {
		var message AgentMessage
		if err := wsjson.Read(ctx, conn, &message); err != nil {
			return
		}
		_ = wsjson.Write(ctx, conn, newMessage("error", map[string]string{"message": "denied"}))
	})
	defer server.Close()

	client := newTestClient(server.URL, 1, 100*time.Millisecond)
	_, err := client.connectAndAuthenticate(context.Background())
	if err == nil || !strings.Contains(err.Error(), "unexpected auth response type") {
		t.Fatalf("connectAndAuthenticate() error = %v, want unexpected response error", err)
	}
}

func TestSendRequiresAuthenticatedConnection(t *testing.T) {
	client := newTestClient("ws://127.0.0.1:1", 1, 100*time.Millisecond)
	if err := client.SendMessage(context.Background(), newMessage("terminal.closed", map[string]string{})); err == nil {
		t.Fatal("SendMessage() succeeded without authentication")
	}
	if err := client.SendMetrics(MetricsPayload{ServerID: 1}); err == nil {
		t.Fatal("SendMetrics() succeeded without authentication")
	}
}

func TestMetricsAckUsesDedicatedHandler(t *testing.T) {
	client := newTestClient("ws://127.0.0.1:1", 42, 100*time.Millisecond)
	acknowledged := make(chan string, 1)
	forwarded := make(chan AgentMessage, 1)
	client.SetMetricsAckHandler(func(messageID string) { acknowledged <- messageID })
	client.SetMessageHandler(func(_ context.Context, message AgentMessage) { forwarded <- message })

	client.handleMessage(context.Background(), AgentMessage{Type: "metrics.ack", MessageID: "ack-1"})

	select {
	case messageID := <-acknowledged:
		if messageID != "ack-1" {
			t.Fatalf("ack message ID = %q, want ack-1", messageID)
		}
	case <-time.After(testTimeout):
		t.Fatal("metrics acknowledgement handler was not called")
	}
	select {
	case message := <-forwarded:
		t.Fatalf("metrics acknowledgement was forwarded to generic handler: %+v", message)
	default:
	}
}

func TestAuthenticatedHandlerCanSendMessage(t *testing.T) {
	server := newTestServer(t, func(ctx context.Context, conn *websocket.Conn) {
		var authenticate AgentMessage
		if err := wsjson.Read(ctx, conn, &authenticate); err != nil {
			return
		}
		if err := wsjson.Write(ctx, conn, newMessage("agent.authenticated", map[string]any{})); err != nil {
			return
		}
		var message AgentMessage
		if err := wsjson.Read(ctx, conn, &message); err != nil {
			t.Errorf("read message from authenticated callback: %v", err)
			return
		}
		if message.Type != "metrics.report" {
			t.Errorf("callback message type = %q, want metrics.report", message.Type)
		}
		<-ctx.Done()
	})
	defer server.Close()

	client := newTestClient(server.URL, 42, 100*time.Millisecond)
	callbackDone := make(chan error, 1)
	client.SetAuthenticatedHandler(func() {
		callbackDone <- client.SendMessage(context.Background(), newMessage("metrics.report", MetricsPayload{ServerID: 42, CollectedAt: "2026-08-03T00:00:00Z"}))
	})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- client.Run(ctx) }()
	select {
	case err := <-callbackDone:
		if err != nil {
			t.Fatalf("authenticated callback SendMessage() error = %v", err)
		}
	case <-time.After(testTimeout):
		t.Fatal("authenticated callback was not called")
	}
	cancel()
	select {
	case err := <-done:
		if err != context.Canceled {
			t.Fatalf("Run() error = %v, want context.Canceled", err)
		}
	case <-time.After(testTimeout):
		t.Fatal("Run() did not stop after cancellation")
	}
}

func TestRunLoopsStopsAfterPeerClose(t *testing.T) {
	clientConn, serverConn := newConnectionPair(t)
	defer serverConn.CloseNow()
	client := newTestClient("ws://127.0.0.1:1", 1, 15*time.Millisecond)

	done := make(chan error, 1)
	go func() { done <- client.runLoops(context.Background(), clientConn) }()
	serverConn.CloseNow()

	select {
	case err := <-done:
		if err == nil {
			t.Fatal("runLoops() error = nil after peer close")
		}
	case <-time.After(testTimeout):
		t.Fatal("runLoops() did not return after peer close")
	}
}

func TestRunRevokesConnectionBeforeDisconnectHandler(t *testing.T) {
	server := newTestServer(t, func(ctx context.Context, conn *websocket.Conn) {
		var message AgentMessage
		if err := wsjson.Read(ctx, conn, &message); err != nil {
			return
		}
		if err := wsjson.Write(ctx, conn, newMessage("agent.authenticated", map[string]any{})); err != nil {
			return
		}
		conn.CloseNow()
	})
	defer server.Close()

	client := newTestClient(server.URL, 1, 250*time.Millisecond)
	disconnected := make(chan error, 1)
	client.SetDisconnectHandler(func() {
		disconnected <- client.SendMetrics(MetricsPayload{ServerID: 1})
	})

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- client.Run(ctx) }()

	select {
	case err := <-disconnected:
		if err == nil || !strings.Contains(err.Error(), "not authenticated") {
			t.Fatalf("disconnect handler SendMetrics() error = %v", err)
		}
	case <-time.After(testTimeout):
		t.Fatal("disconnect handler was not called")
	}
	cancel()
	select {
	case err := <-done:
		if err != context.Canceled {
			t.Fatalf("Run() error = %v, want context.Canceled", err)
		}
	case <-time.After(testTimeout):
		t.Fatal("Run() did not stop after cancellation")
	}
}

func TestRunWaitsBeforeReconnectAfterAuthenticatedDisconnect(t *testing.T) {
	var mu sync.Mutex
	var connectionTimes []time.Time
	secondConnected := make(chan struct{})
	var once sync.Once
	server := newTestServer(t, func(ctx context.Context, conn *websocket.Conn) {
		mu.Lock()
		connectionTimes = append(connectionTimes, time.Now())
		count := len(connectionTimes)
		mu.Unlock()

		var message AgentMessage
		if err := wsjson.Read(ctx, conn, &message); err != nil {
			return
		}
		if err := wsjson.Write(ctx, conn, newMessage("agent.authenticated", map[string]any{})); err != nil {
			return
		}
		if count == 1 {
			conn.CloseNow()
			return
		}
		once.Do(func() { close(secondConnected) })
		<-ctx.Done()
	})
	defer server.Close()

	const backoff = 120 * time.Millisecond
	client := newTestClient(server.URL, 1, backoff)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- client.Run(ctx) }()

	select {
	case <-secondConnected:
	case <-time.After(testTimeout):
		t.Fatal("client did not reconnect")
	}
	mu.Lock()
	if len(connectionTimes) < 2 {
		mu.Unlock()
		t.Fatalf("connection count = %d, want at least 2", len(connectionTimes))
	}
	interval := connectionTimes[1].Sub(connectionTimes[0])
	mu.Unlock()
	// equal jitter 下限为 backoff/2。
	if interval < backoff/2-25*time.Millisecond {
		t.Fatalf("reconnect interval = %s, want at least %s", interval, backoff/2-25*time.Millisecond)
	}

	cancel()
	select {
	case err := <-done:
		if err != context.Canceled {
			t.Fatalf("Run() error = %v, want context.Canceled", err)
		}
	case <-time.After(testTimeout):
		t.Fatal("Run() did not stop after cancellation")
	}
}

func TestRunCancellationInterruptsReconnectBackoff(t *testing.T) {
	client := newTestClient("ws://127.0.0.1:1", 1, time.Second)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- client.Run(ctx) }()

	time.Sleep(50 * time.Millisecond)
	cancel()

	select {
	case err := <-done:
		if err != context.Canceled {
			t.Fatalf("Run() error = %v, want context.Canceled", err)
		}
	case <-time.After(testTimeout):
		t.Fatal("Run() did not stop while waiting for reconnect")
	}
}

func TestWaitBackoffJitterBounds(t *testing.T) {
	const backoff = 100 * time.Millisecond
	ctx := context.Background()
	for i := 0; i < 20; i++ {
		start := time.Now()
		if err := waitBackoff(ctx, backoff); err != nil {
			t.Fatalf("waitBackoff() error = %v", err)
		}
		elapsed := time.Since(start)
		// equal jitter 范围 [backoff/2, backoff]，两侧留调度余量。
		if elapsed < backoff/2-25*time.Millisecond || elapsed > backoff+50*time.Millisecond {
			t.Fatalf("waitBackoff() waited %s, want within [%s, %s]",
				elapsed, backoff/2-25*time.Millisecond, backoff+50*time.Millisecond)
		}
	}
}

func TestWaitBackoffNonPositiveDoesNotPanic(t *testing.T) {
	ctx := context.Background()
	for _, backoff := range []time.Duration{0, -5 * time.Millisecond} {
		done := make(chan error, 1)
		go func() { done <- waitBackoff(ctx, backoff) }()
		select {
		case err := <-done:
			if err != nil {
				t.Fatalf("waitBackoff(%s) error = %v", backoff, err)
			}
		case <-time.After(time.Second):
			t.Fatalf("waitBackoff(%s) did not return", backoff)
		}
	}
}

func TestRunResetsBackoffAfterAuthenticatedCycle(t *testing.T) {
	// 前两次连接鉴权失败（退避翻倍），第三次认证成功后被立即断开。
	// 认证成功必须重置退避：第四次连接的等待应约为 initial（jitter 上限 60ms），
	// 若未重置则为 2*initial（jitter 下限 120ms），两次范围不重叠。
	var mu sync.Mutex
	var connectionTimes []time.Time
	reconnected := make(chan struct{})
	var once sync.Once
	server := newTestServer(t, func(ctx context.Context, conn *websocket.Conn) {
		mu.Lock()
		connectionTimes = append(connectionTimes, time.Now())
		count := len(connectionTimes)
		mu.Unlock()

		var message AgentMessage
		if err := wsjson.Read(ctx, conn, &message); err != nil {
			return
		}
		if count <= 2 {
			_ = wsjson.Write(ctx, conn, newMessage("error", map[string]string{"message": "denied"}))
			return
		}
		if err := wsjson.Write(ctx, conn, newMessage("agent.authenticated", map[string]any{})); err != nil {
			return
		}
		if count == 3 {
			conn.CloseNow()
			return
		}
		once.Do(func() { close(reconnected) })
		<-ctx.Done()
	})
	defer server.Close()

	const backoff = 60 * time.Millisecond
	client := newTestClient(server.URL, 1, backoff)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- client.Run(ctx) }()

	select {
	case <-reconnected:
	case <-time.After(5 * time.Second):
		t.Fatal("client did not reach the fourth connection")
	}
	mu.Lock()
	if len(connectionTimes) < 4 {
		mu.Unlock()
		t.Fatalf("connection count = %d, want at least 4", len(connectionTimes))
	}
	interval := connectionTimes[3].Sub(connectionTimes[2])
	mu.Unlock()
	if interval > backoff+25*time.Millisecond {
		t.Fatalf("reconnect interval = %s, want <= %s (backoff was not reset)", interval, backoff+25*time.Millisecond)
	}
	if interval < backoff/2-25*time.Millisecond {
		t.Fatalf("reconnect interval = %s, want >= %s", interval, backoff/2-25*time.Millisecond)
	}

	cancel()
	select {
	case err := <-done:
		if err != context.Canceled {
			t.Fatalf("Run() error = %v, want context.Canceled", err)
		}
	case <-time.After(testTimeout):
		t.Fatal("Run() did not stop after cancellation")
	}
}

func TestRunReturnsOnAuthInvalidatedError(t *testing.T) {
	var mu sync.Mutex
	connections := 0
	server := newTestServer(t, func(ctx context.Context, conn *websocket.Conn) {
		mu.Lock()
		connections++
		mu.Unlock()
		var message AgentMessage
		if err := wsjson.Read(ctx, conn, &message); err != nil {
			return
		}
		if err := wsjson.Write(ctx, conn, newMessage("agent.authenticated", map[string]any{})); err != nil {
			return
		}
		_ = wsjson.Write(ctx, conn, newMessage("error", map[string]any{"code": 40100, "message": "token revoked"}))
	})
	defer server.Close()

	client := newTestClient(server.URL, 1, 100*time.Millisecond)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- client.Run(ctx) }()

	select {
	case err := <-done:
		if !errors.Is(err, errAuthInvalidated) {
			t.Fatalf("Run() error = %v, want errAuthInvalidated", err)
		}
	case <-time.After(testTimeout):
		t.Fatal("Run() did not return on auth invalidated error")
	}
	mu.Lock()
	defer mu.Unlock()
	if connections != 1 {
		t.Fatalf("connection count = %d, want 1 (no reconnect)", connections)
	}
}

func TestRunContinuesAfterServerErrorFrame(t *testing.T) {
	server := newTestServer(t, func(ctx context.Context, conn *websocket.Conn) {
		var message AgentMessage
		if err := wsjson.Read(ctx, conn, &message); err != nil {
			return
		}
		if err := wsjson.Write(ctx, conn, newMessage("agent.authenticated", map[string]any{})); err != nil {
			return
		}
		_ = wsjson.Write(ctx, conn, newMessage("error", map[string]any{"code": 42902, "message": "rate limited"}))
		<-ctx.Done()
	})
	defer server.Close()

	client := newTestClient(server.URL, 1, 100*time.Millisecond)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- client.Run(ctx) }()

	// 非 40100 的 error 帧只记录日志，连接保持，Run 不应提前返回。
	select {
	case err := <-done:
		t.Fatalf("Run() returned early: %v", err)
	case <-time.After(300 * time.Millisecond):
	}
	cancel()
	select {
	case err := <-done:
		if err != context.Canceled {
			t.Fatalf("Run() error = %v, want context.Canceled", err)
		}
	case <-time.After(testTimeout):
		t.Fatal("Run() did not stop after cancellation")
	}
}

func TestRunSkipsDisconnectHandlerOnShutdown(t *testing.T) {
	authDone := make(chan struct{})
	server := newTestServer(t, func(ctx context.Context, conn *websocket.Conn) {
		var message AgentMessage
		if err := wsjson.Read(ctx, conn, &message); err != nil {
			return
		}
		if err := wsjson.Write(ctx, conn, newMessage("agent.authenticated", map[string]any{})); err != nil {
			return
		}
		close(authDone)
		<-ctx.Done()
	})
	defer server.Close()

	client := newTestClient(server.URL, 1, 100*time.Millisecond)
	var calls atomic.Int32
	client.SetDisconnectHandler(func() { calls.Add(1) })

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- client.Run(ctx) }()

	select {
	case <-authDone:
	case <-time.After(testTimeout):
		t.Fatal("client did not authenticate")
	}
	cancel()
	select {
	case err := <-done:
		if err != context.Canceled {
			t.Fatalf("Run() error = %v, want context.Canceled", err)
		}
	case <-time.After(testTimeout):
		t.Fatal("Run() did not stop after cancellation")
	}
	if calls.Load() != 0 {
		t.Fatalf("disconnect handler calls = %d, want 0 on shutdown", calls.Load())
	}
}

func TestDisconnectHandlerPanicDoesNotStopRun(t *testing.T) {
	var mu sync.Mutex
	connectionCount := 0
	reconnected := make(chan struct{})
	var once sync.Once
	server := newTestServer(t, func(ctx context.Context, conn *websocket.Conn) {
		mu.Lock()
		connectionCount++
		count := connectionCount
		mu.Unlock()

		var message AgentMessage
		if err := wsjson.Read(ctx, conn, &message); err != nil {
			return
		}
		if err := wsjson.Write(ctx, conn, newMessage("agent.authenticated", map[string]any{})); err != nil {
			return
		}
		if count == 1 {
			conn.CloseNow()
			return
		}
		once.Do(func() { close(reconnected) })
		<-ctx.Done()
	})
	defer server.Close()

	client := newTestClient(server.URL, 1, 50*time.Millisecond)
	client.SetDisconnectHandler(func() { panic("boom") })

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- client.Run(ctx) }()

	// 断连回调 panic 应被捕获，重连继续。
	select {
	case <-reconnected:
	case <-time.After(testTimeout):
		t.Fatal("client did not reconnect after disconnect handler panic")
	}
	cancel()
	select {
	case err := <-done:
		if err != context.Canceled {
			t.Fatalf("Run() error = %v, want context.Canceled", err)
		}
	case <-time.After(testTimeout):
		t.Fatal("Run() did not stop after cancellation")
	}
}

func TestRunRejectsInvalidIntervals(t *testing.T) {
	tests := []struct {
		name            string
		heartbeat       time.Duration
		reconnectInit   time.Duration
		reconnectMax    time.Duration
		wantErrContains string
	}{
		{"zero heartbeat", 0, time.Second, time.Second, "heartbeat interval"},
		{"zero reconnect initial", time.Second, 0, time.Second, "reconnect initial"},
		{"max below initial", time.Second, 2 * time.Second, time.Second, "reconnect max"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			client := NewClient("ws://127.0.0.1:1", 1, "test-token", slog.Default(), tt.heartbeat, tt.reconnectInit, tt.reconnectMax)
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			err := client.Run(ctx)
			if err == nil || !strings.Contains(err.Error(), tt.wantErrContains) {
				t.Fatalf("Run() error = %v, want contains %q", err, tt.wantErrContains)
			}
		})
	}
}

func newTestClient(serverURL string, serverID int64, reconnectInitial time.Duration) *Client {
	return NewClient(strings.Replace(serverURL, "http://", "ws://", 1), serverID, "test-token", slog.Default(),
		20*time.Millisecond, reconnectInitial, reconnectInitial)
}

func newTestServer(t *testing.T, handler func(context.Context, *websocket.Conn)) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, nil)
		if err != nil {
			t.Errorf("accept websocket: %v", err)
			return
		}
		defer conn.CloseNow()
		handler(r.Context(), conn)
	}))
}

func newConnectionPair(t *testing.T) (*websocket.Conn, *websocket.Conn) {
	t.Helper()
	accepted := make(chan *websocket.Conn, 1)
	server := newTestServer(t, func(_ context.Context, conn *websocket.Conn) {
		accepted <- conn
		select {}
	})
	t.Cleanup(server.Close)
	url := strings.Replace(server.URL, "http://", "ws://", 1)
	clientConn, _, err := websocket.Dial(context.Background(), url, nil)
	if err != nil {
		t.Fatalf("dial test server: %v", err)
	}
	select {
	case serverConn := <-accepted:
		return clientConn, serverConn
	case <-time.After(testTimeout):
		clientConn.CloseNow()
		t.Fatal("server did not accept test connection")
		return nil, nil
	}
}

func receiveMessage(t *testing.T, messages <-chan AgentMessage) AgentMessage {
	t.Helper()
	select {
	case message := <-messages:
		return message
	case <-time.After(testTimeout):
		t.Fatal("timed out waiting for message")
		return AgentMessage{}
	}
}

func Example_waitBackoff() {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	fmt.Println(waitBackoff(ctx, time.Second) == context.Canceled)
	// Output: true
}
