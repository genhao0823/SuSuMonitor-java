// Package reporter constructs, durably queues, and sends metrics.report messages.
package reporter

import (
	"context"
	"fmt"
	"log/slog"
	"math/rand"
	"sync"
	"time"

	"agent-go-SuMon/internal/collector"
	"agent-go-SuMon/internal/metricbuffer"
	"agent-go-SuMon/internal/wsclient"
)

// MessageSender defines the minimal authenticated WebSocket write operation.
type MessageSender interface {
	SendMessage(context.Context, wsclient.AgentMessage) error
}

// Options controls acknowledgement deadlines and retransmission delays.
type Options struct {
	AckTimeout        time.Duration
	RetryInitial      time.Duration
	RetryMax          time.Duration
	RetryJitter       bool
	ReplayMinInterval time.Duration
	// NackRetryMax 是可重试 nack（retriable_server_error）的最大重试次数，达到上限后死信。
	NackRetryMax int
	// NackRetryInitial 是可重试 nack 的首次退避间隔。
	NackRetryInitial time.Duration
	// NackRetryMaxDelay 是可重试 nack 的最大退避间隔。
	NackRetryMaxDelay time.Duration
}

// Reporter persists metrics before sending and permits only one unacknowledged
// frame at a time. This preserves the server's strict collected_at ordering.
type Reporter struct {
	serverID int64
	logger   *slog.Logger
	sender   MessageSender
	queue    *metricbuffer.Queue
	options  Options

	mu         sync.Mutex
	inFlight   bool
	messageID  string
	generation uint64
	attempts   int
	ackTimer   *time.Timer
	retryTimer *time.Timer
}

// NewReporter creates a reliable metrics reporter backed by queue.
func NewReporter(serverID int64, logger *slog.Logger, sender MessageSender, queue *metricbuffer.Queue,
	options Options) *Reporter {
	return &Reporter{serverID: serverID, logger: logger, sender: sender, queue: queue, options: options}
}

// Report creates a metrics frame, persists it before network I/O, and then
// attempts to send the FIFO head. Offline operation remains locally durable.
func (r *Reporter) Report(metrics collector.Metrics) error {
	payload := wsclient.MetricsPayload{
		ServerID:      r.serverID,
		CollectedAt:   time.Now().UTC().Format(time.RFC3339Nano),
		CPUPercent:    metrics.CPUPercent,
		MemoryPercent: metrics.MemoryPercent,
		MemoryUsed:    metrics.MemoryUsed,
		MemoryTotal:   metrics.MemoryTotal,
		DiskPercent:   metrics.DiskPercent,
		DiskUsed:      metrics.DiskUsed,
		DiskTotal:     metrics.DiskTotal,
		NetRx:         metrics.NetRx,
		NetTx:         metrics.NetTx,
		Temperature:   metrics.Temperature,
		LoadAvg:       metrics.LoadAvg,
	}
	message := wsclient.NewMessage("metrics.report", payload)
	if err := r.queue.Enqueue(message); err != nil {
		return fmt.Errorf("queue metrics: %w", err)
	}
	r.logger.Debug("metrics queued", "server_id", r.serverID, "message_id", message.MessageID,
		"collected_at", payload.CollectedAt)
	return r.TrySend()
}

// TrySend attempts the oldest queued frame when no prior frame is awaiting its
// server acknowledgement or scheduled for retry. A write error keeps it durable.
func (r *Reporter) TrySend() error {
	r.mu.Lock()
	if r.inFlight || r.retryTimer != nil {
		r.mu.Unlock()
		return nil
	}
	message, ok := r.queue.Head()
	if !ok {
		r.mu.Unlock()
		return nil
	}
	r.inFlight = true
	r.messageID = message.MessageID
	r.generation++
	generation := r.generation
	r.attempts++
	attempts := r.attempts
	r.mu.Unlock()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := r.sender.SendMessage(ctx, message); err != nil {
		r.mu.Lock()
		if r.inFlight && r.generation == generation && r.messageID == message.MessageID {
			r.inFlight = false
		}
		r.mu.Unlock()
		return fmt.Errorf("send queued metrics: %w", err)
	}

	r.mu.Lock()
	if r.inFlight && r.generation == generation && r.messageID == message.MessageID {
		r.scheduleAckDeadlineLocked(message.MessageID, generation)
	}
	r.mu.Unlock()
	r.logger.Debug("queued metrics sent; awaiting acknowledgement", "message_id", message.MessageID,
		"attempt", attempts)
	return nil
}

func (r *Reporter) scheduleAckDeadlineLocked(messageID string, generation uint64) {
	if r.ackTimer != nil {
		r.ackTimer.Stop()
	}
	r.ackTimer = time.AfterFunc(r.options.AckTimeout, func() {
		r.handleAckDeadline(messageID, generation)
	})
}

func (r *Reporter) handleAckDeadline(messageID string, generation uint64) {
	r.mu.Lock()
	if !r.inFlight || r.messageID != messageID || r.generation != generation {
		r.mu.Unlock()
		return
	}
	r.inFlight = false
	r.ackTimer = nil
	delay := r.retryDelayLocked()
	attempts := r.attempts
	r.scheduleRetryLocked(messageID, generation, r.jitterDelay(delay))
	r.mu.Unlock()
	r.logger.Warn("metrics acknowledgement timed out; retaining and retrying queued frame",
		"message_id", messageID, "attempt", attempts, "retry_delay", delay)
}

func (r *Reporter) retryDelayLocked() time.Duration {
	delay := r.options.RetryInitial
	for attempt := 1; attempt < r.attempts && delay < r.options.RetryMax; attempt++ {
		delay *= 2
		if delay > r.options.RetryMax {
			return r.options.RetryMax
		}
	}
	return delay
}

func (r *Reporter) jitterDelay(delay time.Duration) time.Duration {
	if !r.options.RetryJitter || delay <= 1 {
		return delay
	}
	half := delay / 2
	return half + time.Duration(rand.Int63n(int64(half)+1))
}

// nackRetryDelay 返回可重试 nack 第 count 次重试的退避间隔：
// NackRetryInitial 起指数翻倍，封顶 NackRetryMaxDelay（抖动由调用方叠加）。
func nackRetryDelay(count int, options Options) time.Duration {
	if count < 1 {
		count = 1
	}
	delay := options.NackRetryInitial
	for attempt := 1; attempt < count; attempt++ {
		if delay >= options.NackRetryMaxDelay {
			return options.NackRetryMaxDelay
		}
		delay *= 2
	}
	if delay > options.NackRetryMaxDelay {
		return options.NackRetryMaxDelay
	}
	return delay
}

func (r *Reporter) scheduleRetryLocked(messageID string, generation uint64, delay time.Duration) {
	if r.retryTimer != nil {
		r.retryTimer.Stop()
	}
	r.retryTimer = time.AfterFunc(delay, func() {
		r.mu.Lock()
		if r.inFlight || r.messageID != messageID || r.generation != generation {
			r.mu.Unlock()
			return
		}
		r.retryTimer = nil
		r.mu.Unlock()
		if err := r.TrySend(); err != nil {
			r.logger.Warn("retry queued metrics failed", "message_id", messageID, "error", err)
		}
	})
}

// HandleMetricsAck removes the FIFO head only for the exact acknowledged ID,
// then advances delivery without holding the reporter state lock during I/O.
func (r *Reporter) HandleMetricsAck(messageID string) {
	acknowledged, err := r.queue.Acknowledge(messageID)
	if err != nil {
		r.logger.Error("persist metrics acknowledgement failed", "message_id", messageID, "error", err)
		return
	}
	if !acknowledged {
		r.logger.Warn("ignored unknown or out-of-order metrics acknowledgement", "message_id", messageID)
		return
	}
	next, backlog := r.queue.Head()
	r.mu.Lock()
	r.clearDeliveryLocked()
	if backlog {
		r.messageID = next.MessageID
		r.scheduleRetryLocked(next.MessageID, r.generation, r.options.ReplayMinInterval)
	}
	r.mu.Unlock()
	r.logger.Debug("metrics acknowledgement persisted", "message_id", messageID)
	if !backlog {
		return
	}
	r.logger.Debug("queued metrics replay scheduled", "message_id", next.MessageID,
		"minimum_interval", r.options.ReplayMinInterval)
}

// HandleMetricsNack grades the FIFO head on the correlated metrics.nack frame:
//
//   - retriable reasons below the retry budget keep the head in place and schedule
//     a bounded backoff retransmission (NackRetryInitial doubling up to
//     NackRetryMaxDelay, with the configured jitter);
//   - permanent reasons (or retries exhausted) durably move the head to the local
//     dead-letter, then delivery advances like an acknowledgement.
//
// Generic error frames never reach here; only correlated metrics.nack frames are
// consumed, so a rejected head is never silently dropped.
func (r *Reporter) HandleMetricsNack(messageID string, nack wsclient.MetricsNack) {
	retried, retryCount, _, rejected, evicted, err := r.queue.RejectOrRetryHead(messageID, nack, r.options.NackRetryMax)
	if err != nil {
		r.logger.Error("persist metrics rejection failed", "message_id", messageID, "error", err)
		return
	}
	if retried {
		next, backlog := r.queue.Head()
		delay := nackRetryDelay(retryCount, r.options)
		r.mu.Lock()
		r.clearDeliveryLocked()
		if backlog {
			r.messageID = next.MessageID
			r.scheduleRetryLocked(next.MessageID, r.generation, r.jitterDelay(delay))
		}
		r.mu.Unlock()
		r.logger.Warn("metrics temporarily rejected; retaining head for bounded retry",
			"message_id", messageID, "reason", nack.Reason, "code", nack.Code,
			"retry_count", retryCount, "retry_delay", delay)
		return
	}
	if !rejected {
		r.logger.Warn("ignored unknown or out-of-order metrics rejection", "message_id", messageID)
		return
	}
	next, backlog := r.queue.Head()
	r.mu.Lock()
	r.clearDeliveryLocked()
	if backlog {
		r.messageID = next.MessageID
		r.scheduleRetryLocked(next.MessageID, r.generation, r.options.ReplayMinInterval)
	}
	r.mu.Unlock()
	r.logger.Warn("metrics permanently rejected; moved to local dead-letter",
		"message_id", messageID, "reason", nack.Reason, "code", nack.Code, "detail", nack.Message)
	if evicted {
		r.logger.Warn("dead-letter capacity reached; oldest dead-letter entry evicted",
			"message_id", messageID)
	}
	if !backlog {
		return
	}
	r.logger.Debug("queued metrics replay scheduled after rejection", "message_id", next.MessageID,
		"minimum_interval", r.options.ReplayMinInterval)
}

// HandleAuthenticated resets a previous connection's delivery state and replays
// the unchanged durable FIFO head after the authenticated connection is usable.
func (r *Reporter) HandleAuthenticated() {
	r.mu.Lock()
	r.clearDeliveryLocked()
	r.mu.Unlock()
	if err := r.TrySend(); err != nil {
		r.logger.Warn("replay queued metrics after authentication failed", "error", err)
	}
}

// HandleDisconnect invalidates timers but leaves the unchanged FIFO head for
// replay on the next authenticated connection.
func (r *Reporter) HandleDisconnect() {
	r.mu.Lock()
	r.clearDeliveryLocked()
	r.mu.Unlock()
}

func (r *Reporter) clearDeliveryLocked() {
	if r.ackTimer != nil {
		r.ackTimer.Stop()
		r.ackTimer = nil
	}
	if r.retryTimer != nil {
		r.retryTimer.Stop()
		r.retryTimer = nil
	}
	r.inFlight = false
	r.messageID = ""
	r.generation++
	r.attempts = 0
}
