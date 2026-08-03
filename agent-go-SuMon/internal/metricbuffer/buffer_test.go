package metricbuffer

import (
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
