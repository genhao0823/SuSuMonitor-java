package wsclient

import (
	"encoding/json"
	"regexp"
	"testing"
)

// TestNewAuthMessage 验证 agent.authenticate 首帧消息的字段和 payload。
func TestNewAuthMessage(t *testing.T) {
	msg := newAuthMessage(42, "test-token")

	if msg.Type != "agent.authenticate" {
		t.Errorf("type = %q, want %q", msg.Type, "agent.authenticate")
	}
	if msg.MessageID == "" {
		t.Error("message_id is empty")
	}
	if msg.Timestamp == "" {
		t.Error("timestamp is empty")
	}

	var payload AuthPayload
	if err := json.Unmarshal(msg.Payload, &payload); err != nil {
		t.Fatalf("unmarshal payload: %v", err)
	}
	if payload.ServerID != 42 {
		t.Errorf("server_id = %d, want 42", payload.ServerID)
	}
	if payload.Token != "test-token" {
		t.Errorf("token = %q, want %q", payload.Token, "test-token")
	}
}

// TestNewHeartbeatMessage 验证 heartbeat 消息的 type 和 message_id。
func TestNewHeartbeatMessage(t *testing.T) {
	msg := newHeartbeatMessage()

	if msg.Type != "heartbeat" {
		t.Errorf("type = %q, want %q", msg.Type, "heartbeat")
	}
	if msg.MessageID == "" {
		t.Error("message_id is empty")
	}
	if msg.Timestamp == "" {
		t.Error("timestamp is empty")
	}
}

// TestNewUUID 验证 UUID v4 格式（版本位 4，变体位 8/9/a/b）。
func TestNewUUID(t *testing.T) {
	uuidRegex := regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)
	seen := make(map[string]bool)

	for i := 0; i < 100; i++ {
		id := newUUID()
		if !uuidRegex.MatchString(id) {
			t.Errorf("uuid %q does not match v4 format", id)
		}
		if seen[id] {
			t.Errorf("uuid collision: %q seen twice", id)
		}
		seen[id] = true
	}
}

// TestNewUUIDFallback 验证 crypto/rand 失败时 fallback 仍产出合法 UUID v4 且不重复。
func TestNewUUIDFallback(t *testing.T) {
	uuidRegex := regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)
	seen := make(map[string]bool)

	for i := 0; i < 100; i++ {
		b := make([]byte, 16)
		id := formatUUID(newUUIDFallbackBytes(b))
		if !uuidRegex.MatchString(id) {
			t.Errorf("fallback uuid %q does not match v4 format", id)
		}
		if seen[id] {
			t.Errorf("fallback uuid collision: %q seen twice", id)
		}
		seen[id] = true
	}
}

// TestMetricsPayloadNullFields 验证 nil 指针字段序列化为 JSON null。
func TestMetricsPayloadNullFields(t *testing.T) {
	cpu := 35.5
	payload := MetricsPayload{
		ServerID:    1,
		CollectedAt: "2026-07-21T12:00:00Z",
		CPUPercent:  &cpu,
		// 其他字段为 nil
	}

	data, err := json.Marshal(payload)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}

	var m map[string]interface{}
	if err := json.Unmarshal(data, &m); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}

	if m["temperature"] != nil {
		t.Errorf("temperature = %v, want nil", m["temperature"])
	}
	if m["load_avg"] != nil {
		t.Errorf("load_avg = %v, want nil", m["load_avg"])
	}
	if m["memory_used"] != nil {
		t.Errorf("memory_used = %v, want nil", m["memory_used"])
	}
	if m["cpu_percent"] != 35.5 {
		t.Errorf("cpu_percent = %v, want 35.5", m["cpu_percent"])
	}
}

// TestAgentMessageJSONFieldNames 验证 JSON 字段名使用 snake_case。
func TestAgentMessageJSONFieldNames(t *testing.T) {
	msg := AgentMessage{
		Type:      "test",
		MessageID: "abc-123",
		Timestamp: "2026-07-21T12:00:00Z",
		Payload:   json.RawMessage(`{}`),
	}

	data, err := json.Marshal(msg)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}

	var m map[string]interface{}
	if err := json.Unmarshal(data, &m); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}

	requiredKeys := []string{"type", "message_id", "timestamp", "payload"}
	for _, key := range requiredKeys {
		if _, ok := m[key]; !ok {
			t.Errorf("missing key %q in JSON output", key)
		}
	}

	// 确保没有 camelCase 泄漏
	forbiddenKeys := []string{"messageId", "requestId", "request_id"}
	for _, key := range forbiddenKeys {
		if _, ok := m[key]; ok {
			t.Errorf("unexpected key %q in JSON output", key)
		}
	}
}
