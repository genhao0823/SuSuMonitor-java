package reporter

import (
	"context"
	"encoding/json"
	"log/slog"
	"path/filepath"
	"testing"

	"agent-go-SuMon/internal/collector"
	"agent-go-SuMon/internal/metricbuffer"
	"agent-go-SuMon/internal/wsclient"
)

type recordingSender struct {
	messages []wsclient.AgentMessage
	err      error
}

func (s *recordingSender) SendMessage(_ context.Context, message wsclient.AgentMessage) error {
	s.messages = append(s.messages, message)
	return s.err
}

// TestReportQueuesBeforeAcknowledgement verifies a frame remains durable until
// its correlated metrics.ack is handled.
func TestReportQueuesBeforeAcknowledgement(t *testing.T) {
	cpu := 35.5
	queue, err := metricbuffer.Open(filepath.Join(t.TempDir(), "metrics.json"), 42, 2)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	sender := &recordingSender{}
	reporter := NewReporter(42, slog.Default(), sender, queue)
	if err := reporter.Report(collector.Metrics{CPUPercent: &cpu}); err != nil {
		t.Fatalf("Report() error = %v", err)
	}
	if len(sender.messages) != 1 {
		t.Fatalf("sent messages = %d, want 1", len(sender.messages))
	}
	if queue.Len() != 1 {
		t.Fatalf("queued messages = %d, want 1 before ack", queue.Len())
	}
	var payload wsclient.MetricsPayload
	if err := json.Unmarshal(sender.messages[0].Payload, &payload); err != nil {
		t.Fatalf("unmarshal metrics payload: %v", err)
	}
	if payload.ServerID != 42 || payload.CPUPercent == nil || *payload.CPUPercent != 35.5 || payload.CollectedAt == "" {
		t.Fatalf("unexpected payload: %+v", payload)
	}

	reporter.HandleMetricsAck(sender.messages[0].MessageID)
	if queue.Len() != 0 {
		t.Fatalf("queued messages = %d, want 0 after ack", queue.Len())
	}
}

// TestReporterKeepsFifoUntilAcknowledged verifies later metrics do not bypass
// the first unacknowledged frame.
func TestReporterKeepsFifoUntilAcknowledged(t *testing.T) {
	queue, err := metricbuffer.Open(filepath.Join(t.TempDir(), "metrics.json"), 42, 3)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	sender := &recordingSender{}
	reporter := NewReporter(42, slog.Default(), sender, queue)
	if err := reporter.Report(collector.Metrics{}); err != nil {
		t.Fatalf("first Report() error = %v", err)
	}
	firstID := sender.messages[0].MessageID
	if err := reporter.Report(collector.Metrics{}); err != nil {
		t.Fatalf("second Report() error = %v", err)
	}
	if len(sender.messages) != 1 {
		t.Fatalf("sent messages before first ack = %d, want 1", len(sender.messages))
	}
	reporter.HandleMetricsAck(firstID)
	if len(sender.messages) != 2 {
		t.Fatalf("sent messages after first ack = %d, want 2", len(sender.messages))
	}
	if sender.messages[0].MessageID == sender.messages[1].MessageID {
		t.Fatal("second metrics frame reused first message ID")
	}
}
