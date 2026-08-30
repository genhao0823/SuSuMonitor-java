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

const snapshotVersion = 3

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

// QueueEntry 是待确认帧及其 nack 重试计数（snapshot v3 起持久化）。
//
// retriable_server_error 类 nack 会在队首原地重试（计数递增），
// 达到上限后与永久拒绝一样移入死信。
type QueueEntry struct {
	Frame     wsclient.AgentMessage `json:"frame"`
	NackCount int                   `json:"nack_count"`
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
	mu           sync.Mutex
	path         string
	serverID     int64
	capacity     int
	maxBytes     int // 0 = 不限制
	entries      []QueueEntry
	deadLetter   []DeadLetterEntry
	drops        uint64
	currentBytes int // 已入队帧的序列化字节合计，启动时初始化，增量维护
}

// snapshot 是当前（v3）持久化格式：entries 携带 nack 重试计数。
type snapshot struct {
	Version    int               `json:"version"`
	ServerID   int64             `json:"server_id"`
	Entries    []QueueEntry      `json:"entries"`
	DeadLetter []DeadLetterEntry `json:"dead_letter,omitempty"`
}

// legacySnapshot 是 v1/v2 持久化格式（entries 为裸帧），仅用于读取迁移。
type legacySnapshot struct {
	Version    int                     `json:"version"`
	ServerID   int64                   `json:"server_id"`
	Entries    []wsclient.AgentMessage `json:"entries"`
	DeadLetter []DeadLetterEntry       `json:"dead_letter,omitempty"`
}

// Open loads the durable queue or creates an empty in-memory queue when no
// snapshot exists. Invalid snapshots fail startup rather than discarding data
// whose delivery state is unknown.
func Open(path string, serverID int64, capacity, maxBytes int) (*Queue, error) {
	if !filepath.IsAbs(path) || filepath.Clean(path) != path {
		return nil, fmt.Errorf("metrics buffer path must be a clean absolute path")
	}
	if serverID <= 0 {
		return nil, fmt.Errorf("metrics buffer server ID must be positive")
	}
	if capacity < 1 {
		return nil, fmt.Errorf("metrics buffer capacity must be positive")
	}
	if maxBytes != 0 && maxBytes < 1024 {
		return nil, fmt.Errorf("metrics buffer max bytes must be 0 (unlimited) or >= 1024")
	}

	queue := &Queue{path: path, serverID: serverID, capacity: capacity, maxBytes: maxBytes}
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return queue, nil
	}
	if err != nil {
		return nil, fmt.Errorf("read metrics buffer: %w", err)
	}

	// 先读版本再按格式解析：v1/v2 的 entries 是裸帧，v3 起是 frame/nack_count 包装
	var raw struct {
		Version int `json:"version"`
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, fmt.Errorf("parse metrics buffer: %w", err)
	}
	if raw.Version < 1 || raw.Version > snapshotVersion {
		return nil, fmt.Errorf("unsupported metrics buffer version: %d", raw.Version)
	}

	if raw.Version >= 3 {
		var stored snapshot
		if err := json.Unmarshal(data, &stored); err != nil {
			return nil, fmt.Errorf("parse metrics buffer: %w", err)
		}
		if stored.ServerID != serverID {
			return nil, fmt.Errorf("metrics buffer server ID %d does not match configured server ID %d", stored.ServerID, serverID)
		}
		if len(stored.Entries) > capacity {
			return nil, fmt.Errorf("metrics buffer has %d entries, exceeding configured capacity %d", len(stored.Entries), capacity)
		}
		if len(stored.DeadLetter) > capacity {
			return nil, fmt.Errorf("metrics buffer has %d dead-letter entries, exceeding configured capacity %d", len(stored.DeadLetter), capacity)
		}
		for index, entry := range stored.Entries {
			if err := validateMessage(entry.Frame, serverID); err != nil {
				return nil, fmt.Errorf("metrics buffer entry %d: %w", index, err)
			}
		}
		for index, entry := range stored.DeadLetter {
			if err := validateMessage(entry.Frame, serverID); err != nil {
				return nil, fmt.Errorf("metrics buffer dead-letter entry %d: %w", index, err)
			}
		}
		queue.entries = stored.Entries
		queue.deadLetter = stored.DeadLetter
		queue.currentBytes = framesBytes(entryFrames(stored.Entries))
		return queue, nil
	}

	// v1/v2 快照：裸帧读取，原地升级为 v3（NackCount=0）并持久化一次，不丢弃任何待确认数据。
	var stored legacySnapshot
	if err := json.Unmarshal(data, &stored); err != nil {
		return nil, fmt.Errorf("parse metrics buffer: %w", err)
	}
	if stored.ServerID != serverID {
		return nil, fmt.Errorf("metrics buffer server ID %d does not match configured server ID %d", stored.ServerID, serverID)
	}
	if len(stored.Entries) > capacity {
		return nil, fmt.Errorf("metrics buffer has %d entries, exceeding configured capacity %d", len(stored.Entries), capacity)
	}
	if len(stored.DeadLetter) > capacity {
		return nil, fmt.Errorf("metrics buffer has %d dead-letter entries, exceeding configured capacity %d", len(stored.DeadLetter), capacity)
	}
	queue.entries = make([]QueueEntry, len(stored.Entries))
	for index, message := range stored.Entries {
		if err := validateMessage(message, serverID); err != nil {
			return nil, fmt.Errorf("metrics buffer entry %d: %w", index, err)
		}
		queue.entries[index] = QueueEntry{Frame: message}
	}
	for index, entry := range stored.DeadLetter {
		if err := validateMessage(entry.Frame, serverID); err != nil {
			return nil, fmt.Errorf("metrics buffer dead-letter entry %d: %w", index, err)
		}
	}
	queue.deadLetter = stored.DeadLetter
	queue.currentBytes = framesBytes(stored.Entries)
	if err := queue.persist(queue.entries, queue.deadLetter); err != nil {
		return nil, fmt.Errorf("migrate metrics buffer: %w", err)
	}
	return queue, nil
}

// entryFrames 提取队列项的原始帧列表，用于字节统计。
func entryFrames(entries []QueueEntry) []wsclient.AgentMessage {
	frames := make([]wsclient.AgentMessage, len(entries))
	for index, entry := range entries {
		frames[index] = entry.Frame
	}
	return frames
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
	if q.maxBytes > 0 {
		newFrameBytes := frameSize(message)
		if q.currentBytes+newFrameBytes > q.maxBytes {
			q.drops++
			return fmt.Errorf("metrics buffer exceeds byte limit (%d bytes); newest metric was not queued", q.maxBytes)
		}
	}
	entries := append(append([]QueueEntry(nil), q.entries...), QueueEntry{Frame: message})
	if err := q.persist(entries, q.deadLetter); err != nil {
		return err
	}
	q.entries = entries
	q.currentBytes += frameSize(message)
	return nil
}

// Head returns the oldest unacknowledged frame without removing it.
func (q *Queue) Head() (wsclient.AgentMessage, bool) {
	q.mu.Lock()
	defer q.mu.Unlock()
	if len(q.entries) == 0 {
		return wsclient.AgentMessage{}, false
	}
	return q.entries[0].Frame, true
}

// Acknowledge durably removes the FIFO head only when messageID matches it.
// Unknown, stale, and out-of-order acknowledgements leave the queue unchanged.
func (q *Queue) Acknowledge(messageID string) (bool, error) {
	q.mu.Lock()
	defer q.mu.Unlock()
	if len(q.entries) == 0 || q.entries[0].Frame.MessageID != messageID {
		return false, nil
	}
	removed := q.entries[0]
	entries := append([]QueueEntry(nil), q.entries[1:]...)
	if err := q.persist(entries, q.deadLetter); err != nil {
		return false, err
	}
	q.entries = entries
	q.currentBytes -= frameSize(removed.Frame)
	if q.currentBytes < 0 {
		q.currentBytes = 0
	}
	return true, nil
}

// RejectHead durably moves the FIFO head to the local dead-letter when
// messageID matches it and the nack is permanent (or retry budget exhausted).
// It is a shortcut of RejectOrRetryHead with zero retries.
func (q *Queue) RejectHead(messageID string, nack wsclient.MetricsNack) (DeadLetterEntry, bool, bool, error) {
	_, _, entry, rejected, evicted, err := q.RejectOrRetryHead(messageID, nack, 0)
	return entry, rejected, evicted, err
}

// RejectOrRetryHead grades the FIFO head on metrics.nack:
//
//   - retriable reasons (retriable_server_error) below the retry budget keep the
//     head in place with an incremented NackCount, returning retried=true so the
//     caller can schedule a bounded backoff retransmission;
//   - permanent reasons (or retries exhausted) move the head to the local
//     dead-letter, returning rejected=true with the created entry.
//
// The returned evicted flag reports whether the oldest dead-letter entry was
// dropped because the shared capacity bound was reached.
func (q *Queue) RejectOrRetryHead(messageID string, nack wsclient.MetricsNack, maxRetries int) (bool, int, DeadLetterEntry, bool, bool, error) {
	q.mu.Lock()
	defer q.mu.Unlock()
	if len(q.entries) == 0 || q.entries[0].Frame.MessageID != messageID {
		return false, 0, DeadLetterEntry{}, false, false, nil
	}
	head := q.entries[0]
	if maxRetries > 0 && isRetriableReason(nack.Reason) && head.NackCount < maxRetries {
		// 可重试原因且未达上限：保留队首（严格时序不受影响），计数 +1 并持久化
		entries := append([]QueueEntry(nil), q.entries...)
		entries[0].NackCount++
		if err := q.persist(entries, q.deadLetter); err != nil {
			return false, 0, DeadLetterEntry{}, false, false, err
		}
		q.entries = entries
		return true, entries[0].NackCount, DeadLetterEntry{}, false, false, nil
	}
	entry := DeadLetterEntry{
		Frame:      head.Frame,
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
	entries := append([]QueueEntry(nil), q.entries[1:]...)
	if err := q.persist(entries, deadLetter); err != nil {
		return false, 0, DeadLetterEntry{}, false, false, err
	}
	q.entries = entries
	q.deadLetter = deadLetter
	q.currentBytes -= frameSize(entry.Frame)
	if q.currentBytes < 0 {
		q.currentBytes = 0
	}
	return false, 0, entry, true, evicted, nil
}

// isRetriableReason 判定 nack 原因是否属于可重试分类。
//
// 当前仅 retriable_server_error（服务端入库阶段临时故障）；其余原因重发不会
// 改变拒绝条件（载荷非法/采样时间过期/服务器不存在），直接死信。
func isRetriableReason(reason string) bool {
	return reason == "retriable_server_error"
}

// Stats returns a delivery telemetry snapshot of the queue and dead-letter.
func (q *Queue) Stats() Stats {
	q.mu.Lock()
	defer q.mu.Unlock()
	return Stats{
		PendingCount:      len(q.entries),
		PendingBytes:      q.currentBytes,
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

func (q *Queue) persist(entries []QueueEntry, deadLetter []DeadLetterEntry) error {
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
	if !isValidUTCTime(message.Timestamp) {
		return fmt.Errorf("message timestamp must be UTC ISO-8601")
	}
	var payload wsclient.MetricsPayload
	if err := json.Unmarshal(message.Payload, &payload); err != nil {
		return fmt.Errorf("parse metrics payload: %w", err)
	}
	if payload.ServerID != serverID {
		return fmt.Errorf("metrics payload server ID %d does not match %d", payload.ServerID, serverID)
	}
	if !isValidUTCTime(payload.CollectedAt) {
		return fmt.Errorf("metrics payload collected_at must be UTC ISO-8601")
	}
	return nil
}

// isValidUTCTime 校验字符串为 UTC ISO-8601（RFC3339Nano 可解析且时区偏移为 0）。
// 拒绝无时区偏移或非 UTC 偏移的时间，保证快照/队列时间语义与协议一致。
func isValidUTCTime(value string) bool {
	parsed, err := time.Parse(time.RFC3339Nano, value)
	if err != nil {
		return false
	}
	_, offset := parsed.Zone()
	return offset == 0
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
		total += frameSize(message)
	}
	return total
}

// frameSize 返回单帧序列化后的字节数，用于字节上限检查与增量统计。
func frameSize(msg wsclient.AgentMessage) int {
	data, err := json.Marshal(msg)
	if err != nil {
		return 0
	}
	return len(data)
}

// oldestCollectedAt 返回队首帧的 collected_at；无法解析时返回空字符串。
func oldestCollectedAt(entries []QueueEntry) string {
	if len(entries) == 0 {
		return ""
	}
	var payload wsclient.MetricsPayload
	if err := json.Unmarshal(entries[0].Frame.Payload, &payload); err != nil {
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
