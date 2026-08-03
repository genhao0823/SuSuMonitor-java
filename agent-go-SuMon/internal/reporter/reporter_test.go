package reporter

import (
	"context"
	"encoding/json"
	"log/slog"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"agent-go-SuMon/internal/collector"
	"agent-go-SuMon/internal/metricbuffer"
	"agent-go-SuMon/internal/wsclient"
)

type recordingSender struct {
	mu       sync.Mutex
	messages []wsclient.AgentMessage
	err      error
}

func (s *recordingSender) SendMessage(_ context.Context, message wsclient.AgentMessage) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.messages = append(s.messages, message)
	return s.err
}

func (s *recordingSender) snapshot() []wsclient.AgentMessage {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]wsclient.AgentMessage(nil), s.messages...)
}

func newTestReporter(t *testing.T, sender *recordingSender, options Options) (*Reporter, *metricbuffer.Queue) {
	t.Helper()
	queue, err := metricbuffer.Open(filepath.Join(t.TempDir(), "metrics.json"), 42, 3)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	return NewReporter(42, slog.Default(), sender, queue, options), queue
}

func retryOptions() Options {
	return Options{AckTimeout: 20 * time.Millisecond, RetryInitial: 10 * time.Millisecond, RetryMax: 40 * time.Millisecond}
}

func waitForMessages(t *testing.T, sender *recordingSender, count int) []wsclient.AgentMessage {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		messages := sender.snapshot()
		if len(messages) >= count {
			return messages
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("sent messages = %d, want at least %d", len(sender.snapshot()), count)
	return nil
}

// TestReportQueuesBeforeAcknowledgement verifies a frame remains durable until
// its correlated metrics.ack is handled.
func TestReportQueuesBeforeAcknowledgement(t *testing.T) {
	cpu := 35.5
	sender := &recordingSender{}
	reporter, queue := newTestReporter(t, sender, retryOptions())
	if err := reporter.Report(collector.Metrics{CPUPercent: &cpu}); err != nil {
		t.Fatalf("Report() error = %v", err)
	}
	messages := waitForMessages(t, sender, 1)
	if queue.Len() != 1 {
		t.Fatalf("queued messages = %d, want 1 before ack", queue.Len())
	}
	var payload wsclient.MetricsPayload
	if err := json.Unmarshal(messages[0].Payload, &payload); err != nil {
		t.Fatalf("unmarshal metrics payload: %v", err)
	}
	if payload.ServerID != 42 || payload.CPUPercent == nil || *payload.CPUPercent != 35.5 || payload.CollectedAt == "" {
		t.Fatalf("unexpected payload: %+v", payload)
	}

	reporter.HandleMetricsAck(messages[0].MessageID)
	if queue.Len() != 0 {
		t.Fatalf("queued messages = %d, want 0 after ack", queue.Len())
	}
}

// TestReporterKeepsFifoUntilAcknowledged verifies later metrics do not bypass
// the first unacknowledged frame.
func TestReporterKeepsFifoUntilAcknowledged(t *testing.T) {
	sender := &recordingSender{}
	reporter, _ := newTestReporter(t, sender, retryOptions())
	if err := reporter.Report(collector.Metrics{}); err != nil {
		t.Fatalf("first Report() error = %v", err)
	}
	firstID := waitForMessages(t, sender, 1)[0].MessageID
	if err := reporter.Report(collector.Metrics{}); err != nil {
		t.Fatalf("second Report() error = %v", err)
	}
	if len(sender.snapshot()) != 1 {
		t.Fatalf("sent messages before first ack = %d, want 1", len(sender.snapshot()))
	}
	reporter.HandleMetricsAck(firstID)
	messages := waitForMessages(t, sender, 2)
	if messages[0].MessageID == messages[1].MessageID {
		t.Fatal("second metrics frame reused first message ID")
	}
}

// TestReporterRetriesSameMessageAfterAcknowledgementTimeout verifies a lost
// acknowledgement retransmits the durable head with its original UUID.
func TestReporterRetriesSameMessageAfterAcknowledgementTimeout(t *testing.T) {
	sender := &recordingSender{}
	reporter, queue := newTestReporter(t, sender, retryOptions())
	if err := reporter.Report(collector.Metrics{}); err != nil {
		t.Fatalf("Report() error = %v", err)
	}
	messages := waitForMessages(t, sender, 2)
	if messages[0].MessageID != messages[1].MessageID {
		t.Fatalf("retry message IDs = %q and %q, want original ID reuse", messages[0].MessageID, messages[1].MessageID)
	}
	if queue.Len() != 1 {
		t.Fatalf("queued messages after timeout retry = %d, want 1", queue.Len())
	}
	reporter.HandleMetricsAck(messages[0].MessageID)
}

// TestReporterAcknowledgementCancelsDeadline verifies an accepted frame cannot
// be retransmitted by its former deadline after acknowledgement.
func TestReporterAcknowledgementCancelsDeadline(t *testing.T) {
	sender := &recordingSender{}
	reporter, queue := newTestReporter(t, sender, retryOptions())
	if err := reporter.Report(collector.Metrics{}); err != nil {
		t.Fatalf("Report() error = %v", err)
	}
	messageID := waitForMessages(t, sender, 1)[0].MessageID
	reporter.HandleMetricsAck(messageID)
	time.Sleep(80 * time.Millisecond)
	if len(sender.snapshot()) != 1 {
		t.Fatalf("sent messages after acknowledgement = %d, want 1", len(sender.snapshot()))
	}
	if queue.Len() != 0 {
		t.Fatalf("queued messages after acknowledgement = %d, want 0", queue.Len())
	}
}

// TestReporterAuthenticationInvalidatesPriorDeadline verifies a reconnect
// cancels the stale timer and sends exactly one replay of the FIFO head.
func TestReporterAuthenticationInvalidatesPriorDeadline(t *testing.T) {
	sender := &recordingSender{}
	reporter, _ := newTestReporter(t, sender, Options{AckTimeout: 40 * time.Millisecond, RetryInitial: 10 * time.Millisecond, RetryMax: 40 * time.Millisecond})
	if err := reporter.Report(collector.Metrics{}); err != nil {
		t.Fatalf("Report() error = %v", err)
	}
	firstID := waitForMessages(t, sender, 1)[0].MessageID
	reporter.HandleDisconnect()
	reporter.HandleAuthenticated()
	messages := waitForMessages(t, sender, 2)
	if messages[1].MessageID != firstID {
		t.Fatalf("replayed message ID = %q, want %q", messages[1].MessageID, firstID)
	}
	time.Sleep(55 * time.Millisecond)
	if len(sender.snapshot()) != 3 {
		t.Fatalf("sent messages after new deadline = %d, want 3", len(sender.snapshot()))
	}
}

// TestReporterPacesBacklogAfterAcknowledgement verifies the next queued frame
// waits for the configured replay interval instead of draining at ACK speed.
func TestReporterPacesBacklogAfterAcknowledgement(t *testing.T) {
	sender := &recordingSender{}
	options := Options{AckTimeout: time.Second, RetryInitial: time.Millisecond, RetryMax: time.Second,
		ReplayMinInterval: 45 * time.Millisecond}
	reporter, _ := newTestReporter(t, sender, options)
	if err := reporter.Report(collector.Metrics{}); err != nil {
		t.Fatalf("first Report() error = %v", err)
	}
	firstID := waitForMessages(t, sender, 1)[0].MessageID
	if err := reporter.Report(collector.Metrics{}); err != nil {
		t.Fatalf("second Report() error = %v", err)
	}

	started := time.Now()
	reporter.HandleMetricsAck(firstID)
	time.Sleep(20 * time.Millisecond)
	if len(sender.snapshot()) != 1 {
		t.Fatalf("backlog replay ignored minimum interval; sent %d messages", len(sender.snapshot()))
	}
	messages := waitForMessages(t, sender, 2)
	if elapsed := time.Since(started); elapsed < options.ReplayMinInterval-10*time.Millisecond {
		t.Fatalf("backlog replay delay = %s, want at least %s", elapsed, options.ReplayMinInterval-10*time.Millisecond)
	}
	if messages[0].MessageID == messages[1].MessageID {
		t.Fatal("second queued metric did not advance after acknowledgement")
	}
}

// TestReporterJitterDelay verifies disabled retry jitter preserves the exact
// retry delay and enabled jitter stays within the equal-jitter interval.
func TestReporterJitterDelay(t *testing.T) {
	sender := &recordingSender{}
	reporter, _ := newTestReporter(t, sender, Options{RetryJitter: false})
	if actual := reporter.jitterDelay(20 * time.Millisecond); actual != 20*time.Millisecond {
		t.Fatalf("disabled jitter delay = %s, want 20ms", actual)
	}
	reporter.options.RetryJitter = true
	for index := 0; index < 20; index++ {
		actual := reporter.jitterDelay(20 * time.Millisecond)
		if actual < 10*time.Millisecond || actual > 20*time.Millisecond {
			t.Fatalf("jitter delay = %s, want within [10ms, 20ms]", actual)
		}
	}
}
