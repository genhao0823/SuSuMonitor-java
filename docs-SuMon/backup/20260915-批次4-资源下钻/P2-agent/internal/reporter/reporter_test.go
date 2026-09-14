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
	queue, err := metricbuffer.Open(filepath.Join(t.TempDir(), "metrics.json"), 42, 3, 0)
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

// TestReportCarriesProcessTopArrays verifies optional top-process arrays are
// serialized as protocol v1.4 fields and omitted entirely when absent.
func TestReportCarriesProcessTopArrays(t *testing.T) {
	sender := &recordingSender{}
	reporter, _ := newTestReporter(t, sender, retryOptions())
	processCPUTop := []collector.ProcessSample{{PID: 9, Name: "java", CPUPercent: 60, MemPercent: 40}}
	processMemTop := []collector.ProcessSample{{PID: 5, Name: "mysqld", CPUPercent: 1.5, MemPercent: 72.25}}
	if err := reporter.Report(collector.Metrics{ProcessCPUTop: &processCPUTop, ProcessMemTop: &processMemTop}); err != nil {
		t.Fatalf("Report() error = %v", err)
	}
	messages := waitForMessages(t, sender, 1)
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(messages[0].Payload, &raw); err != nil {
		t.Fatalf("unmarshal metrics payload: %v", err)
	}
	var cpuTop, memTop []wsclient.ProcessPayload
	if err := json.Unmarshal(raw["process_cpu_top"], &cpuTop); err != nil {
		t.Fatalf("unmarshal process_cpu_top: %v", err)
	}
	if err := json.Unmarshal(raw["process_mem_top"], &memTop); err != nil {
		t.Fatalf("unmarshal process_mem_top: %v", err)
	}
	if len(cpuTop) != 1 || cpuTop[0].PID != 9 || cpuTop[0].Name != "java" ||
		cpuTop[0].CPUPercent != 60 || cpuTop[0].MemPercent != 40 {
		t.Fatalf("unexpected process_cpu_top: %+v", cpuTop)
	}
	if len(memTop) != 1 || memTop[0].PID != 5 || memTop[0].Name != "mysqld" || memTop[0].MemPercent != 72.25 {
		t.Fatalf("unexpected process_mem_top: %+v", memTop)
	}

	sender2 := &recordingSender{}
	reporter2, _ := newTestReporter(t, sender2, retryOptions())
	if err := reporter2.Report(collector.Metrics{}); err != nil {
		t.Fatalf("Report() error = %v", err)
	}
	var absent map[string]json.RawMessage
	if err := json.Unmarshal(waitForMessages(t, sender2, 1)[0].Payload, &absent); err != nil {
		t.Fatalf("unmarshal metrics payload: %v", err)
	}
	if _, ok := absent["process_cpu_top"]; ok {
		t.Fatal("process_cpu_top should be omitted when no samples collected")
	}
	if _, ok := absent["process_mem_top"]; ok {
		t.Fatal("process_mem_top should be omitted when no samples collected")
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

// TestReporterDeadLettersPermanentlyRejectedHead verifies a correlated
// metrics.nack moves the FIFO head to the dead-letter and advances delivery.
func TestReporterDeadLettersPermanentlyRejectedHead(t *testing.T) {
	sender := &recordingSender{}
	reporter, queue := newTestReporter(t, sender, retryOptions())
	if err := reporter.Report(collector.Metrics{}); err != nil {
		t.Fatalf("first Report() error = %v", err)
	}
	firstID := waitForMessages(t, sender, 1)[0].MessageID
	if err := reporter.Report(collector.Metrics{}); err != nil {
		t.Fatalf("second Report() error = %v", err)
	}

	reporter.HandleMetricsNack(firstID, wsclient.MetricsNack{
		ServerID: 42, Code: 40002, Reason: "stale_collected_at", Message: "stale"})
	messages := waitForMessages(t, sender, 2)
	if messages[1].MessageID == firstID {
		t.Fatal("second queued metric did not advance after rejection")
	}
	if queue.Len() != 1 {
		t.Fatalf("queued messages after rejection = %d, want 1", queue.Len())
	}
	stats := queue.Stats()
	if stats.DeadLetterCount != 1 || stats.DeadLetterBytes <= 0 {
		t.Fatalf("dead-letter stats = %+v, want one durable record", stats)
	}
}

// TestReporterIgnoresOutOfOrderRejection verifies an unrelated metrics.nack
// cannot remove the FIFO head and trigger delivery of the next frame.
func TestReporterIgnoresOutOfOrderRejection(t *testing.T) {
	sender := &recordingSender{}
	reporter, queue := newTestReporter(t, sender, retryOptions())
	if err := reporter.Report(collector.Metrics{}); err != nil {
		t.Fatalf("Report() error = %v", err)
	}
	firstID := waitForMessages(t, sender, 1)[0].MessageID

	reporter.HandleMetricsNack("unknown-message-id", wsclient.MetricsNack{ServerID: 42, Reason: "server_not_found"})
	if queue.Len() != 1 {
		t.Fatalf("queued messages after unknown rejection = %d, want 1", queue.Len())
	}
	head, ok := queue.Head()
	if !ok || head.MessageID != firstID {
		t.Fatalf("head after unknown rejection = %+v, want original first entry", head)
	}
	if stats := queue.Stats(); stats.DeadLetterCount != 0 {
		t.Fatalf("dead-letter count = %d, want 0", stats.DeadLetterCount)
	}
}

// TestReporterRetriesRetriableNackThenDelivers verifies a retriable nack keeps
// the FIFO head and retransmits the same message after the nack backoff; a
// subsequent acknowledgement then removes it from the queue.
func TestReporterRetriesRetriableNackThenDelivers(t *testing.T) {
	sender := &recordingSender{}
	options := retryOptions()
	options.NackRetryMax = 3
	options.NackRetryInitial = 10 * time.Millisecond
	options.NackRetryMaxDelay = 40 * time.Millisecond
	reporter, queue := newTestReporter(t, sender, options)
	if err := reporter.Report(collector.Metrics{}); err != nil {
		t.Fatalf("Report() error = %v", err)
	}
	firstID := waitForMessages(t, sender, 1)[0].MessageID

	reporter.HandleMetricsNack(firstID, wsclient.MetricsNack{
		ServerID: 42, Code: 50001, Reason: "retriable_server_error", Message: "db hiccup"})
	messages := waitForMessages(t, sender, 2)
	if messages[1].MessageID != firstID {
		t.Fatalf("retransmission message id = %s, want retained head %s", messages[1].MessageID, firstID)
	}
	if queue.Len() != 1 {
		t.Fatalf("queued messages after retriable nack = %d, want 1 (head retained)", queue.Len())
	}

	reporter.HandleMetricsAck(firstID)
	if queue.Len() != 0 {
		t.Fatalf("queued messages after ack = %d, want 0", queue.Len())
	}
}

// TestReporterDeadLettersRetriableNackAfterBudget verifies retriable nacks
// beyond the retry budget move the head to the local dead-letter and delivery
// advances to the next queued frame.
func TestReporterDeadLettersRetriableNackAfterBudget(t *testing.T) {
	sender := &recordingSender{}
	options := retryOptions()
	options.NackRetryMax = 1
	options.NackRetryInitial = 10 * time.Millisecond
	options.NackRetryMaxDelay = 40 * time.Millisecond
	reporter, queue := newTestReporter(t, sender, options)
	if err := reporter.Report(collector.Metrics{}); err != nil {
		t.Fatalf("first Report() error = %v", err)
	}
	firstID := waitForMessages(t, sender, 1)[0].MessageID
	if err := reporter.Report(collector.Metrics{}); err != nil {
		t.Fatalf("second Report() error = %v", err)
	}
	nack := wsclient.MetricsNack{ServerID: 42, Code: 50001, Reason: "retriable_server_error", Message: "db hiccup"}

	// 第 1 次：预算内 → 重发同一队首
	reporter.HandleMetricsNack(firstID, nack)
	messages := waitForMessages(t, sender, 2)
	if messages[1].MessageID != firstID {
		t.Fatalf("first retry message id = %s, want %s", messages[1].MessageID, firstID)
	}

	// 第 2 次：预算耗尽 → 死信并推进到下一帧
	reporter.HandleMetricsNack(firstID, nack)
	messages = waitForMessages(t, sender, 3)
	if messages[2].MessageID == firstID {
		t.Fatal("head was not advanced after retry budget exhaustion")
	}
	if queue.Len() != 1 {
		t.Fatalf("queued messages after budget exhaustion = %d, want 1", queue.Len())
	}
	stats := queue.Stats()
	if stats.DeadLetterCount != 1 {
		t.Fatalf("dead-letter count = %d, want 1", stats.DeadLetterCount)
	}
}

// TestReporterNackRetryDelayDoublesAndCaps verifies the nack backoff helper
// doubles from the initial value and caps at the configured maximum.
func TestReporterNackRetryDelayDoublesAndCaps(t *testing.T) {
	options := Options{NackRetryInitial: 10 * time.Millisecond, NackRetryMaxDelay: 40 * time.Millisecond}
	if got := nackRetryDelay(1, options); got != 10*time.Millisecond {
		t.Fatalf("nackRetryDelay(1) = %v, want 10ms", got)
	}
	if got := nackRetryDelay(2, options); got != 20*time.Millisecond {
		t.Fatalf("nackRetryDelay(2) = %v, want 20ms", got)
	}
	if got := nackRetryDelay(5, options); got != 40*time.Millisecond {
		t.Fatalf("nackRetryDelay(5) = %v, want capped 40ms", got)
	}
}
