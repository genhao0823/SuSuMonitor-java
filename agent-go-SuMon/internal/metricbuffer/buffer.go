// Package metricbuffer persists unacknowledged metrics.report frames in strict FIFO order.
package metricbuffer

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"agent-go-SuMon/internal/wsclient"
)

const snapshotVersion = 2

// DeadLetterEntry 是被服务端 metrics.nack 永久拒绝的完整指标帧及其拒绝原因。
//
// 服务端仅对可关联且永久无效的指标返回 NACK，因此死信记录不会重试。
type DeadLetterEntry struct {
	Frame      wsclient.AgentMessage `json:"frame"`
	Reason     string                `json:"reason"`
	Code       int                   `json:"code"`
	Detail     string                `json:"detail"`
	RejectedAt string                `json:"rejected_at"`
}

// Stats 是队列与死信的投递遥测快照，供心跳暴露与管理端展示。
type Stats struct {
	PendingCount      int
	PendingBytes      int
	OldestCollectedAt string
	DropCount         uint64
	DeadLetterCount   int
	DeadLetterBytes   int
}

// Queue keeps a bounded, durable FIFO of metrics.report frames.
//
// A record is removed only after the server returns metrics.ack for its original
// message_id, or metrics.nack moves it to the local dead-letter. Strict FIFO is
// required because the server rejects per-server collected_at values that are
// not strictly increasing.
type Queue struct {
	mu         sync.Mutex
	path       string
	serverID   int64
	capacity   int
	entries    []wsclient.AgentMessage
	deadLetter []DeadLetterEntry
	drops      uint64
}

type snapshot struct {
	Version    int                     `json:"version"`
	ServerID   int64                   `json:"server_id"`
	Entries    []wsclient.AgentMessage `json:"entries"`
	DeadLetter []DeadLetterEntry       `json:"dead_letter,omitempty"`
}

// Open loads the durable queue or creates an empty in-memory queue when no
// snapshot exists. Invalid snapshots fail startup rather than discarding data
// whose delivery state is unknown.
func Open(path string, serverID int64, capacity int) (*Queue, error) {
	if !filepath.IsAbs(path) || filepath.Clean(path) != path {
		return nil, fmt.Errorf("metrics buffer path must be a clean absolute path")
	}
	if serverID <= 0 {
		return nil, fmt.Errorf("metrics buffer server ID must be positive")
	}
	if capacity < 1 {
		return nil, fmt.Errorf("metrics buffer capacity must be positive")
	}

	queue := &Queue{path: path, serverID: serverID, capacity: capacity}
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return queue, nil
	}
	if err != nil {
		return nil, fmt.Errorf("read metrics buffer: %w", err)
	}

	var stored snapshot
	if err := json.Unmarshal(data, &stored); err != nil {
		return nil, fmt.Errorf("parse metrics buffer: %w", err)
	}
	if stored.Version < 1 || stored.Version > snapshotVersion {
		return nil, fmt.Errorf("unsupported metrics buffer version: %d", stored.Version)
	}
	if stored.ServerID != serverID {
		return nil, fmt.Errorf("metrics buffer server ID %d does not match configured server ID %d", stored.ServerID, serverID)
	}
	if len(stored.Entries) > capacity {
		return nil, fmt.Errorf("metrics buffer has %d entries, exceeding configured capacity %d", len(stored.Entries), capacity)
	}
	for index, message := range stored.Entries {
		if err := validateMessage(message, serverID); err != nil {
			return nil, fmt.Errorf("metrics buffer entry %d: %w", index, err)
		}
	}
	if len(stored.DeadLetter) > capacity {
		return nil, fmt.Errorf("metrics buffer has %d dead-letter entries, exceeding configured capacity %d", len(stored.DeadLetter), capacity)
	}
	for index, entry := range stored.DeadLetter {
		if err := validateMessage(entry.Frame, serverID); err != nil {
			return nil, fmt.Errorf("metrics buffer dead-letter entry %d: %w", index, err)
		}
	}
	queue.entries = stored.Entries
	queue.deadLetter = stored.DeadLetter
	// v1 快照没有死信字段：原地升级为 v2 并持久化一次，不丢弃任何待确认数据。
	if stored.Version < snapshotVersion {
		if err := queue.persist(queue.entries, queue.deadLetter); err != nil {
			return nil, fmt.Errorf("migrate metrics buffer: %w", err)
		}
	}
	return queue, nil
}

// Enqueue durably appends one complete metrics.report frame. It never replaces
// or reorders older entries, preserving the server's timestamp ordering rule.
func (q *Queue) Enqueue(message wsclient.AgentMessage) error {
	if err := validateMessage(message, q.serverID); err != nil {
		return err
	}
	q.mu.Lock()
	defer q.mu.Unlock()
	if len(q.entries) >= q.capacity {
		q.drops++
		return fmt.Errorf("metrics buffer is full (%d entries); newest metric was not queued", q.capacity)
	}
	entries := append(append([]wsclient.AgentMessage(nil), q.entries...), message)
	if err := q.persist(entries, q.deadLetter); err != nil {
		return err
	}
	q.entries = entries
	return nil
}

// Head returns the oldest unacknowledged frame without removing it.
func (q *Queue) Head() (wsclient.AgentMessage, bool) {
	q.mu.Lock()
	defer q.mu.Unlock()
	if len(q.entries) == 0 {
		return wsclient.AgentMessage{}, false
	}
	return q.entries[0], true
}

// Acknowledge durably removes the FIFO head only when messageID matches it.
// Unknown, stale, and out-of-order acknowledgements leave the queue unchanged.
func (q *Queue) Acknowledge(messageID string) (bool, error) {
	q.mu.Lock()
	defer q.mu.Unlock()
	if len(q.entries) == 0 || q.entries[0].MessageID != messageID {
		return false, nil
	}
	entries := append([]wsclient.AgentMessage(nil), q.entries[1:]...)
	if err := q.persist(entries, q.deadLetter); err != nil {
		return false, err
	}
	q.entries = entries
	return true, nil
}

// RejectHead durably moves the FIFO head to the local dead-letter when
// messageID matches it. The server returns metrics.nack only for correlated,
// permanently invalid metrics, so a rejected head is never retried.
//
// The returned evicted flag reports whether the oldest dead-letter entry was
// dropped because the shared capacity bound was reached.
func (q *Queue) RejectHead(messageID string, nack wsclient.MetricsNack) (DeadLetterEntry, bool, bool, error) {
	q.mu.Lock()
	defer q.mu.Unlock()
	if len(q.entries) == 0 || q.entries[0].MessageID != messageID {
		return DeadLetterEntry{}, false, false, nil
	}
	entry := DeadLetterEntry{
		Frame:      q.entries[0],
		Reason:     nack.Reason,
		Code:       nack.Code,
		Detail:     nack.Message,
		RejectedAt: time.Now().UTC().Format(time.RFC3339Nano),
	}
	deadLetter := append(append([]DeadLetterEntry(nil), q.deadLetter...), entry)
	evicted := false
	if len(deadLetter) > q.capacity {
		// 死信与队列共用容量上限，超限时丢弃最旧死信而不是最新队列项。
		deadLetter = deadLetter[len(deadLetter)-q.capacity:]
		evicted = true
	}
	entries := append([]wsclient.AgentMessage(nil), q.entries[1:]...)
	if err := q.persist(entries, deadLetter); err != nil {
		return DeadLetterEntry{}, false, false, err
	}
	q.entries = entries
	q.deadLetter = deadLetter
	return entry, true, evicted, nil
}

// Stats returns a delivery telemetry snapshot of the queue and dead-letter.
func (q *Queue) Stats() Stats {
	q.mu.Lock()
	defer q.mu.Unlock()
	return Stats{
		PendingCount:      len(q.entries),
		PendingBytes:      framesBytes(q.entries),
		OldestCollectedAt: oldestCollectedAt(q.entries),
		DropCount:         q.drops,
		DeadLetterCount:   len(q.deadLetter),
		DeadLetterBytes:   deadLetterBytes(q.deadLetter),
	}
}

// Len returns the current number of unacknowledged frames.
func (q *Queue) Len() int {
	q.mu.Lock()
	defer q.mu.Unlock()
	return len(q.entries)
}

func (q *Queue) persist(entries []wsclient.AgentMessage, deadLetter []DeadLetterEntry) error {
	data, err := json.Marshal(snapshot{Version: snapshotVersion, ServerID: q.serverID, Entries: entries, DeadLetter: deadLetter})
	if err != nil {
		return fmt.Errorf("marshal metrics buffer: %w", err)
	}
	if err := os.MkdirAll(filepath.Dir(q.path), 0o700); err != nil {
		return fmt.Errorf("create metrics buffer directory: %w", err)
	}
	temporary := q.path + ".tmp"
	file, err := os.OpenFile(temporary, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return fmt.Errorf("open metrics buffer temporary file: %w", err)
	}
	if _, err := file.Write(data); err != nil {
		file.Close()
		return fmt.Errorf("write metrics buffer: %w", err)
	}
	if err := file.Sync(); err != nil {
		file.Close()
		return fmt.Errorf("sync metrics buffer: %w", err)
	}
	if err := file.Close(); err != nil {
		return fmt.Errorf("close metrics buffer: %w", err)
	}
	if err := os.Chmod(temporary, 0o600); err != nil {
		return fmt.Errorf("protect metrics buffer: %w", err)
	}
	if err := os.Rename(temporary, q.path); err != nil {
		return fmt.Errorf("replace metrics buffer: %w", err)
	}
	return nil
}

func validateMessage(message wsclient.AgentMessage, serverID int64) error {
	if message.Type != "metrics.report" {
		return fmt.Errorf("message type must be metrics.report")
	}
	if !isUUID(message.MessageID) {
		return fmt.Errorf("message ID must be a UUID")
	}
	if message.Timestamp == "" {
		return fmt.Errorf("message timestamp is required")
	}
	var payload wsclient.MetricsPayload
	if err := json.Unmarshal(message.Payload, &payload); err != nil {
		return fmt.Errorf("parse metrics payload: %w", err)
	}
	if payload.ServerID != serverID {
		return fmt.Errorf("metrics payload server ID %d does not match %d", payload.ServerID, serverID)
	}
	if payload.CollectedAt == "" {
		return fmt.Errorf("metrics payload collected_at is required")
	}
	return nil
}

func isUUID(value string) bool {
	if len(value) != 36 {
		return false
	}
	for index, character := range value {
		if index == 8 || index == 13 || index == 18 || index == 23 {
			if character != '-' {
				return false
			}
			continue
		}
		if !strings.ContainsRune("0123456789abcdefABCDEF", character) {
			return false
		}
	}
	return true
}

// framesBytes 返回队列帧的序列化字节数合计，用于投递遥测中的 pending bytes。
func framesBytes(entries []wsclient.AgentMessage) int {
	total := 0
	for _, message := range entries {
		data, err := json.Marshal(message)
		if err == nil {
			total += len(data)
		}
	}
	return total
}

// oldestCollectedAt 返回队首帧的 collected_at；无法解析时返回空字符串。
func oldestCollectedAt(entries []wsclient.AgentMessage) string {
	if len(entries) == 0 {
		return ""
	}
	var payload wsclient.MetricsPayload
	if err := json.Unmarshal(entries[0].Payload, &payload); err != nil {
		return ""
	}
	return payload.CollectedAt
}

// deadLetterBytes 返回死信记录的序列化字节数合计，用于投递遥测中的 dead-letter bytes。
func deadLetterBytes(entries []DeadLetterEntry) int {
	total := 0
	for _, entry := range entries {
		data, err := json.Marshal(entry)
		if err == nil {
			total += len(data)
		}
	}
	return total
}
