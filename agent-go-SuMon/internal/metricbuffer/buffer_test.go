package metricbuffer

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"agent-go-SuMon/internal/wsclient"
)

func metricsMessage(id string, serverID int64, collectedAt string) wsclient.AgentMessage {
	return wsclient.NewMessageWithID("metrics.report", id, wsclient.MetricsPayload{
		ServerID:    serverID,
		CollectedAt: collectedAt,
	})
}

// TestQueuePersistsFifoAcrossRestart verifies original IDs and collection order
// survive process restart before any server acknowledgement.
func TestQueuePersistsFifoAcrossRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "metrics.json")
	queue, err := Open(path, 42, 3, 0)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	first := metricsMessage("11111111-1111-4111-8111-111111111111", 42, "2026-08-03T00:00:00Z")
	second := metricsMessage("22222222-2222-4222-8222-222222222222", 42, "2026-08-03T00:00:05Z")
	if err := queue.Enqueue(first); err != nil {
		t.Fatalf("enqueue first: %v", err)
	}
	if err := queue.Enqueue(second); err != nil {
		t.Fatalf("enqueue second: %v", err)
	}

	reopened, err := Open(path, 42, 3, 0)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	head, ok := reopened.Head()
	if !ok || head.MessageID != first.MessageID {
		t.Fatalf("head = %+v, want first message", head)
	}
	acknowledged, err := reopened.Acknowledge(first.MessageID)
	if err != nil || !acknowledged {
		t.Fatalf("acknowledge first = %v, %v", acknowledged, err)
	}
	head, ok = reopened.Head()
	if !ok || head.MessageID != second.MessageID {
		t.Fatalf("head after acknowledgement = %+v, want second message", head)
	}
}

// TestQueueRejectsOutOfOrderAcknowledgement verifies only the current FIFO head
// can be removed, preventing a malformed ACK from losing queued metrics.
func TestQueueRejectsOutOfOrderAcknowledgement(t *testing.T) {
	queue, err := Open(filepath.Join(t.TempDir(), "metrics.json"), 42, 2, 0)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	first := metricsMessage("11111111-1111-4111-8111-111111111111", 42, "2026-08-03T00:00:00Z")
	second := metricsMessage("22222222-2222-4222-8222-222222222222", 42, "2026-08-03T00:00:05Z")
	_ = queue.Enqueue(first)
	_ = queue.Enqueue(second)
	acknowledged, err := queue.Acknowledge(second.MessageID)
	if err != nil || acknowledged {
		t.Fatalf("out-of-order acknowledge = %v, %v; want false, nil", acknowledged, err)
	}
	if queue.Len() != 2 {
		t.Fatalf("queue length = %d, want 2", queue.Len())
	}
}

// TestQueueRejectsNewestOnCapacity preserves the durable FIFO prefix when the
// configured bound is reached.
func TestQueueRejectsNewestOnCapacity(t *testing.T) {
	queue, err := Open(filepath.Join(t.TempDir(), "metrics.json"), 42, 1, 0)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	first := metricsMessage("11111111-1111-4111-8111-111111111111", 42, "2026-08-03T00:00:00Z")
	second := metricsMessage("22222222-2222-4222-8222-222222222222", 42, "2026-08-03T00:00:05Z")
	if err := queue.Enqueue(first); err != nil {
		t.Fatalf("enqueue first: %v", err)
	}
	if err := queue.Enqueue(second); err == nil {
		t.Fatal("enqueue second succeeded with full queue")
	}
	head, ok := queue.Head()
	if !ok || head.MessageID != first.MessageID {
		t.Fatalf("head = %+v, want original first entry", head)
	}
}

func rejection(reason string) wsclient.MetricsNack {
	return wsclient.MetricsNack{ServerID: 42, Code: 40002, Reason: reason, Message: "rejected"}
}

// TestQueueRejectHeadMovesToDeadLetter verifies a correlated metrics.nack
// durably moves the FIFO head into the local dead-letter and advances the queue.
func TestQueueRejectHeadMovesToDeadLetter(t *testing.T) {
	queue, err := Open(filepath.Join(t.TempDir(), "metrics.json"), 42, 3, 0)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	first := metricsMessage("11111111-1111-4111-8111-111111111111", 42, "2026-08-03T00:00:00Z")
	second := metricsMessage("22222222-2222-4222-8222-222222222222", 42, "2026-08-03T00:00:05Z")
	_ = queue.Enqueue(first)
	_ = queue.Enqueue(second)

	entry, rejected, evicted, err := queue.RejectHead(first.MessageID, rejection("stale_collected_at"))
	if err != nil || !rejected || evicted {
		t.Fatalf("RejectHead() = rejected %v, evicted %v, error %v; want true, false, nil", rejected, evicted, err)
	}
	if entry.Frame.MessageID != first.MessageID || entry.Reason != "stale_collected_at" || entry.RejectedAt == "" {
		t.Fatalf("dead-letter entry = %+v, want first frame with rejection reason", entry)
	}
	head, ok := queue.Head()
	if !ok || head.MessageID != second.MessageID {
		t.Fatalf("head after rejection = %+v, want second message", head)
	}
	if queue.Len() != 1 {
		t.Fatalf("queue length = %d, want 1", queue.Len())
	}
}

// TestQueueRejectHeadRejectsOutOfOrder verifies only the current FIFO head can
// be dead-lettered, preventing an unrelated NACK from losing queued metrics.
func TestQueueRejectHeadRejectsOutOfOrder(t *testing.T) {
	queue, err := Open(filepath.Join(t.TempDir(), "metrics.json"), 42, 3, 0)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	first := metricsMessage("11111111-1111-4111-8111-111111111111", 42, "2026-08-03T00:00:00Z")
	_ = queue.Enqueue(first)
	_, rejected, _, err := queue.RejectHead("unknown-message-id", rejection("server_not_found"))
	if err != nil || rejected {
		t.Fatalf("RejectHead() = rejected %v, error %v; want false, nil", rejected, err)
	}
	if queue.Len() != 1 {
		t.Fatalf("queue length = %d, want 1", queue.Len())
	}
	if stats := queue.Stats(); stats.DeadLetterCount != 0 {
		t.Fatalf("dead-letter count = %d, want 0", stats.DeadLetterCount)
	}
}

// TestQueueMigratesV1Snapshot verifies a pre-dead-letter v1 snapshot is loaded
// and rewritten as v2 without losing any pending entries.
func TestQueueMigratesV1Snapshot(t *testing.T) {
	path := filepath.Join(t.TempDir(), "metrics.json")
	first := metricsMessage("11111111-1111-4111-8111-111111111111", 42, "2026-08-03T00:00:00Z")
	data, err := json.Marshal(struct {
		Version  int                     `json:"version"`
		ServerID int64                   `json:"server_id"`
		Entries  []wsclient.AgentMessage `json:"entries"`
	}{Version: 1, ServerID: 42, Entries: []wsclient.AgentMessage{first}})
	if err != nil {
		t.Fatalf("marshal v1 snapshot: %v", err)
	}
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatalf("write v1 snapshot: %v", err)
	}

	queue, err := Open(path, 42, 3, 0)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	head, ok := queue.Head()
	if !ok || head.MessageID != first.MessageID {
		t.Fatalf("head after migration = %+v, want first message", head)
	}
	migrated, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read migrated snapshot: %v", err)
	}
	var stored snapshot
	if err := json.Unmarshal(migrated, &stored); err != nil {
		t.Fatalf("parse migrated snapshot: %v", err)
	}
	if stored.Version != 3 {
		t.Fatalf("migrated snapshot version = %d, want 3", stored.Version)
	}
	if len(stored.DeadLetter) != 0 {
		t.Fatalf("migrated dead-letter count = %d, want 0", len(stored.DeadLetter))
	}
}

// TestQueueDeadLetterCapsAtCapacity verifies the shared capacity bound evicts
// the oldest dead-letter entry instead of the newest queue entry.
func TestQueueDeadLetterCapsAtCapacity(t *testing.T) {
	queue, err := Open(filepath.Join(t.TempDir(), "metrics.json"), 42, 2, 0)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	first := metricsMessage("11111111-1111-4111-8111-111111111111", 42, "2026-08-03T00:00:00Z")
	second := metricsMessage("22222222-2222-4222-8222-222222222222", 42, "2026-08-03T00:00:05Z")
	third := metricsMessage("33333333-3333-4333-8333-333333333333", 42, "2026-08-03T00:00:10Z")
	for _, message := range []wsclient.AgentMessage{first, second, third} {
		if err := queue.Enqueue(message); err != nil {
			t.Fatalf("enqueue %s: %v", message.MessageID, err)
		}
		if _, rejected, evicted, err := queue.RejectHead(message.MessageID, rejection("server_not_found")); err != nil || !rejected {
			t.Fatalf("RejectHead(%s) = rejected %v, error %v", message.MessageID, rejected, err)
		} else if message.MessageID == third.MessageID && !evicted {
			t.Fatal("third rejection did not evict the oldest dead-letter entry")
		}
	}
	stats := queue.Stats()
	if stats.DeadLetterCount != 2 {
		t.Fatalf("dead-letter count = %d, want 2", stats.DeadLetterCount)
	}
	if stats.PendingCount != 0 {
		t.Fatalf("pending count = %d, want 0", stats.PendingCount)
	}
}

// TestQueueDeadLetterPersistsAcrossRestart verifies rejected frames survive a
// process restart, keeping permanent-rejection evidence locally durable.
func TestQueueDeadLetterPersistsAcrossRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "metrics.json")
	queue, err := Open(path, 42, 3, 0)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	first := metricsMessage("11111111-1111-4111-8111-111111111111", 42, "2026-08-03T00:00:00Z")
	_ = queue.Enqueue(first)
	entry, rejected, _, err := queue.RejectHead(first.MessageID, rejection("invalid_metrics_payload"))
	if err != nil || !rejected {
		t.Fatalf("RejectHead() = rejected %v, error %v", rejected, err)
	}

	reopened, err := Open(path, 42, 3, 0)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	stats := reopened.Stats()
	if stats.DeadLetterCount != 1 {
		t.Fatalf("dead-letter count after restart = %d, want 1", stats.DeadLetterCount)
	}
	if _, ok := reopened.Head(); ok {
		t.Fatal("queue still has pending entries after restart")
	}
	if stats.DeadLetterBytes <= len(entry.Frame.MessageID) {
		t.Fatalf("dead-letter bytes = %d, want a serialized record", stats.DeadLetterBytes)
	}
}

// TestQueueStats verifies the delivery telemetry snapshot reports pending,
// oldest sample, drop, and dead-letter measurements.
func TestQueueStats(t *testing.T) {
	queue, err := Open(filepath.Join(t.TempDir(), "metrics.json"), 42, 1, 0)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	first := metricsMessage("11111111-1111-4111-8111-111111111111", 42, "2026-08-03T00:00:00Z")
	second := metricsMessage("22222222-2222-4222-8222-222222222222", 42, "2026-08-03T00:00:05Z")
	_ = queue.Enqueue(first)
	if err := queue.Enqueue(second); err == nil {
		t.Fatal("enqueue second succeeded with full queue")
	}
	stats := queue.Stats()
	if stats.PendingCount != 1 || stats.PendingBytes <= 0 || stats.OldestCollectedAt != "2026-08-03T00:00:00Z" {
		t.Fatalf("pending stats = %+v, want one pending frame", stats)
	}
	if stats.DropCount != 1 {
		t.Fatalf("drop count = %d, want 1", stats.DropCount)
	}
	if _, rejected, _, err := queue.RejectHead(first.MessageID, rejection("stale_collected_at")); err != nil || !rejected {
		t.Fatalf("RejectHead() = rejected %v, error %v", rejected, err)
	}
	stats = queue.Stats()
	if stats.PendingCount != 0 || stats.DeadLetterCount != 1 || stats.DeadLetterBytes <= 0 {
		t.Fatalf("post-rejection stats = %+v, want empty queue with one dead-letter", stats)
	}
}

// TestQueueRejectsOnByteCap verifies a configured byte limit rejects the newest
// frame, keeps the durable prefix, and counts the drop.
func TestQueueRejectsOnByteCap(t *testing.T) {
	first := largeMetricsMessage("11111111-1111-4111-8111-111111111111", 42, "2026-08-03T00:00:00Z")
	second := largeMetricsMessage("22222222-2222-4222-8222-222222222222", 42, "2026-08-03T00:00:05Z")
	queue, err := Open(filepath.Join(t.TempDir(), "metrics.json"), 42, 10, frameSize(first))
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	if err := queue.Enqueue(first); err != nil {
		t.Fatalf("enqueue first: %v", err)
	}
	// 上限恰好容纳第一条；第二条必然超出。
	if err := queue.Enqueue(second); err == nil {
		t.Fatal("enqueue second succeeded despite byte limit")
	}
	head, ok := queue.Head()
	if !ok || head.MessageID != first.MessageID {
		t.Fatalf("head = %+v, want retained first entry", head)
	}
	if stats := queue.Stats(); stats.DropCount != 1 {
		t.Fatalf("drop count = %d, want 1", stats.DropCount)
	}
}

// TestBytesTrackedAfterAcknowledge verifies currentBytes shrinks when the FIFO
// head is acknowledged.
func TestBytesTrackedAfterAcknowledge(t *testing.T) {
	queue, err := Open(filepath.Join(t.TempDir(), "metrics.json"), 42, 10, 0)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	first := metricsMessage("11111111-1111-4111-8111-111111111111", 42, "2026-08-03T00:00:00Z")
	second := metricsMessage("22222222-2222-4222-8222-222222222222", 42, "2026-08-03T00:00:05Z")
	_ = queue.Enqueue(first)
	_ = queue.Enqueue(second)
	before := queue.Stats().PendingBytes
	if _, err := queue.Acknowledge(first.MessageID); err != nil {
		t.Fatalf("acknowledge first: %v", err)
	}
	after := queue.Stats().PendingBytes
	if after >= before {
		t.Fatalf("pending bytes after ack = %d, want less than %d", after, before)
	}
}

// TestBytesTrackedAfterRejectHead verifies currentBytes shrinks when the FIFO
// head is dead-lettered, and the remaining frame still respects the byte limit.
func TestBytesTrackedAfterRejectHead(t *testing.T) {
	queue, err := Open(filepath.Join(t.TempDir(), "metrics.json"), 42, 10, 0)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	first := metricsMessage("11111111-1111-4111-8111-111111111111", 42, "2026-08-03T00:00:00Z")
	second := metricsMessage("22222222-2222-4222-8222-222222222222", 42, "2026-08-03T00:00:05Z")
	_ = queue.Enqueue(first)
	_ = queue.Enqueue(second)
	before := queue.Stats().PendingBytes
	if _, rejected, _, err := queue.RejectHead(first.MessageID, rejection("server_not_found")); err != nil || !rejected {
		t.Fatalf("RejectHead() = rejected %v, error %v", rejected, err)
	}
	after := queue.Stats().PendingBytes
	if after >= before {
		t.Fatalf("pending bytes after rejection = %d, want less than %d", after, before)
	}
}

// TestByteCapAndCountCapIndependent verifies the byte and count limits can each
// reject a frame on their own.
func TestByteCapAndCountCapIndependent(t *testing.T) {
	countQueue, err := Open(filepath.Join(t.TempDir(), "count.json"), 42, 1, 1_000_000)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	first := metricsMessage("11111111-1111-4111-8111-111111111111", 42, "2026-08-03T00:00:00Z")
	second := metricsMessage("22222222-2222-4222-8222-222222222222", 42, "2026-08-03T00:00:05Z")
	if err := countQueue.Enqueue(first); err != nil {
		t.Fatalf("enqueue first: %v", err)
	}
	if err := countQueue.Enqueue(second); err == nil {
		t.Fatal("count cap did not reject the second frame")
	}

	large := largeMetricsMessage("33333333-3333-4333-8333-333333333333", 42, "2026-08-03T00:00:10Z")
	byteQueue, err := Open(filepath.Join(t.TempDir(), "bytes.json"), 42, 10, frameSize(large))
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	if err := byteQueue.Enqueue(large); err != nil {
		t.Fatalf("enqueue first: %v", err)
	}
	if err := byteQueue.Enqueue(first); err == nil {
		t.Fatal("byte cap did not reject the second frame")
	}
}

// largeMetricsMessage 构造带超长未知 payload 字段的帧，使单帧序列化字节数远大于
// 最低字节上限(1024)，便于确定性验证字节上限拒绝（校验只解析 MetricsPayload
// 已知字段，未知字段被忽略）。
func largeMetricsMessage(id string, serverID int64, collectedAt string) wsclient.AgentMessage {
	return wsclient.NewMessageWithID("metrics.report", id, struct {
		ServerID    int64  `json:"server_id"`
		CollectedAt string `json:"collected_at"`
		Filler      string `json:"x_filler_ignored_by_metrics_payload"`
	}{
		ServerID: serverID, CollectedAt: collectedAt, Filler: strings.Repeat("x", 2000),
	})
}

// TestQueueRetriableNackKeepsHeadWithCounter verifies a retriable nack below the
// retry budget keeps the FIFO head and increments its persisted counter,
// including across a process restart.
func TestQueueRetriableNackKeepsHeadWithCounter(t *testing.T) {
	path := filepath.Join(t.TempDir(), "metrics.json")
	queue, err := Open(path, 42, 3, 0)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	first := metricsMessage("11111111-1111-4111-8111-111111111111", 42, "2026-08-03T00:00:00Z")
	second := metricsMessage("22222222-2222-4222-8222-222222222222", 42, "2026-08-03T00:00:05Z")
	if err := queue.Enqueue(first); err != nil {
		t.Fatalf("enqueue first: %v", err)
	}
	if err := queue.Enqueue(second); err != nil {
		t.Fatalf("enqueue second: %v", err)
	}
	nack := wsclient.MetricsNack{ServerID: 42, Code: 50001, Reason: "retriable_server_error", Message: "db hiccup"}

	retried, count, _, rejected, _, err := queue.RejectOrRetryHead(first.MessageID, nack, 3)
	if err != nil || !retried || count != 1 || rejected {
		t.Fatalf("first RejectOrRetryHead() = retried %v, count %d, rejected %v, error %v; want true, 1, false, nil", retried, count, rejected, err)
	}
	if queue.Len() != 2 {
		t.Fatalf("queued messages after retry = %d, want 2 (head retained)", queue.Len())
	}
	head, _ := queue.Head()
	if head.MessageID != first.MessageID {
		t.Fatalf("head after retry = %s, want first message retained in place", head.MessageID)
	}

	// 第二次重试计数 +1
	retried, count, _, _, _, err = queue.RejectOrRetryHead(first.MessageID, nack, 3)
	if err != nil || !retried || count != 2 {
		t.Fatalf("second RejectOrRetryHead() = retried %v, count %d, error %v; want true, 2, nil", retried, count, err)
	}

	// 重试计数跨重启持久化（snapshot v3）
	reopened, err := Open(path, 42, 3, 0)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	retried, count, _, _, _, err = reopened.RejectOrRetryHead(first.MessageID, nack, 3)
	if err != nil || !retried || count != 3 {
		t.Fatalf("reopened RejectOrRetryHead() = retried %v, count %d, error %v; want true, 3, nil", retried, count, err)
	}
}

// TestQueueRetriableNackExhaustsBudgetThenDeadLetter verifies the head moves to
// the local dead-letter once the retry budget is exhausted.
func TestQueueRetriableNackExhaustsBudgetThenDeadLetter(t *testing.T) {
	queue, err := Open(filepath.Join(t.TempDir(), "metrics.json"), 42, 3, 0)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	first := metricsMessage("11111111-1111-4111-8111-111111111111", 42, "2026-08-03T00:00:00Z")
	if err := queue.Enqueue(first); err != nil {
		t.Fatalf("enqueue: %v", err)
	}
	nack := wsclient.MetricsNack{ServerID: 42, Code: 50001, Reason: "retriable_server_error", Message: "db hiccup"}

	for wantCount := 1; wantCount <= 2; wantCount++ {
		retried, count, _, rejected, _, err := queue.RejectOrRetryHead(first.MessageID, nack, 2)
		if err != nil || !retried || count != wantCount || rejected {
			t.Fatalf("RejectOrRetryHead(%d) = retried %v, count %d, rejected %v, error %v", wantCount, retried, count, rejected, err)
		}
	}
	// 第 3 次（超预算）→ 死信
	_, _, entry, rejected, evicted, err := queue.RejectOrRetryHead(first.MessageID, nack, 2)
	if err != nil || !rejected || evicted {
		t.Fatalf("budget-exhausted RejectOrRetryHead() = rejected %v, evicted %v, error %v; want true, false, nil", rejected, evicted, err)
	}
	if queue.Len() != 0 {
		t.Fatalf("queued messages after rejection = %d, want 0", queue.Len())
	}
	stats := queue.Stats()
	if stats.DeadLetterCount != 1 || entry.Reason != "retriable_server_error" {
		t.Fatalf("dead-letter stats = %+v, entry reason = %s; want one retriable record", stats, entry.Reason)
	}
}

// TestQueuePermanentNackDeadLettersImmediately verifies permanent reasons bypass
// the retry budget entirely.
func TestQueuePermanentNackDeadLettersImmediately(t *testing.T) {
	queue, err := Open(filepath.Join(t.TempDir(), "metrics.json"), 42, 3, 0)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	first := metricsMessage("11111111-1111-4111-8111-111111111111", 42, "2026-08-03T00:00:00Z")
	if err := queue.Enqueue(first); err != nil {
		t.Fatalf("enqueue: %v", err)
	}

	retried, count, _, rejected, _, err := queue.RejectOrRetryHead(
		first.MessageID, rejection("server_not_found"), 5)
	if err != nil || retried || count != 0 || !rejected {
		t.Fatalf("permanent RejectOrRetryHead() = retried %v, count %d, rejected %v, error %v; want false, 0, true, nil", retried, count, rejected, err)
	}
	if queue.Len() != 0 {
		t.Fatalf("queued messages after rejection = %d, want 0", queue.Len())
	}
}

// TestIsValidUTCTime verifies the UTC ISO-8601 gate rejects local times,
// non-UTC offsets, and unparseable strings.
func TestIsValidUTCTime(t *testing.T) {
	valid := []string{
		"2026-08-29T08:00:00Z",
		"2026-08-29T08:00:00.123Z",
		"2026-08-29T08:00:00.123456789Z",
		"2026-08-29T08:00:00+00:00",
	}
	for _, value := range valid {
		if !isValidUTCTime(value) {
			t.Errorf("isValidUTCTime(%q) = false, want true", value)
		}
	}
	invalid := []string{
		"",                          // 空
		"2026-08-29T08:00:00",       // 无时区偏移
		"2026-08-29T08:00:00+08:00", // 非 UTC 偏移
		"2026-08-29",                // 仅日期
		"not-a-time",                // 不可解析
	}
	for _, value := range invalid {
		if isValidUTCTime(value) {
			t.Errorf("isValidUTCTime(%q) = true, want false", value)
		}
	}
}

// TestOpenRejectsNonUTCTimes verifies snapshot loading fails on non-UTC
// collected_at / timestamp instead of silently accepting corrupted data.
func TestOpenRejectsNonUTCTimes(t *testing.T) {
	path := filepath.Join(t.TempDir(), "metrics.json")
	// 手工构造非 UTC 偏移的 collected_at 帧（绕过 Enqueue 校验直接持久化）。
	frame := metricsMessage("11111111-1111-4111-8111-111111111111", 42, "2026-08-03T08:00:00+08:00")
	raw, err := json.Marshal(snapshot{Version: snapshotVersion, ServerID: 42,
		Entries: []QueueEntry{{Frame: frame}}})
	if err != nil {
		t.Fatalf("marshal snapshot: %v", err)
	}
	if err := os.WriteFile(path, raw, 0o600); err != nil {
		t.Fatalf("write snapshot: %v", err)
	}

	if _, err := Open(path, 42, 3, 0); err == nil {
		t.Fatal("Open() accepted non-UTC collected_at snapshot, want error")
	}
}
