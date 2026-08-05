package metricbuffer

import (
	"encoding/json"
	"os"
	"path/filepath"
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
	queue, err := Open(path, 42, 3)
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

	reopened, err := Open(path, 42, 3)
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
	queue, err := Open(filepath.Join(t.TempDir(), "metrics.json"), 42, 2)
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
	queue, err := Open(filepath.Join(t.TempDir(), "metrics.json"), 42, 1)
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
	queue, err := Open(filepath.Join(t.TempDir(), "metrics.json"), 42, 3)
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
	queue, err := Open(filepath.Join(t.TempDir(), "metrics.json"), 42, 3)
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

	queue, err := Open(path, 42, 3)
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
	if stored.Version != 2 {
		t.Fatalf("migrated snapshot version = %d, want 2", stored.Version)
	}
	if len(stored.DeadLetter) != 0 {
		t.Fatalf("migrated dead-letter count = %d, want 0", len(stored.DeadLetter))
	}
}

// TestQueueDeadLetterCapsAtCapacity verifies the shared capacity bound evicts
// the oldest dead-letter entry instead of the newest queue entry.
func TestQueueDeadLetterCapsAtCapacity(t *testing.T) {
	queue, err := Open(filepath.Join(t.TempDir(), "metrics.json"), 42, 2)
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
	queue, err := Open(path, 42, 3)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	first := metricsMessage("11111111-1111-4111-8111-111111111111", 42, "2026-08-03T00:00:00Z")
	_ = queue.Enqueue(first)
	entry, rejected, _, err := queue.RejectHead(first.MessageID, rejection("invalid_metrics_payload"))
	if err != nil || !rejected {
		t.Fatalf("RejectHead() = rejected %v, error %v", rejected, err)
	}

	reopened, err := Open(path, 42, 3)
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
	queue, err := Open(filepath.Join(t.TempDir(), "metrics.json"), 42, 1)
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
