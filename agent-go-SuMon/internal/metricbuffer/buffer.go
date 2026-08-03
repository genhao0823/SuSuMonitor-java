// Package metricbuffer persists unacknowledged metrics.report frames in strict FIFO order.
package metricbuffer

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"agent-go-SuMon/internal/wsclient"
)

const snapshotVersion = 1

// Queue keeps a bounded, durable FIFO of metrics.report frames.
//
// A record is removed only after the server returns metrics.ack for its original
// message_id. Strict FIFO is required because the server rejects per-server
// collected_at values that are not strictly increasing.
type Queue struct {
	mu       sync.Mutex
	path     string
	serverID int64
	capacity int
	entries  []wsclient.AgentMessage
}

type snapshot struct {
	Version  int                     `json:"version"`
	ServerID int64                   `json:"server_id"`
	Entries  []wsclient.AgentMessage `json:"entries"`
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
	if stored.Version != snapshotVersion {
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
	queue.entries = stored.Entries
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
		return fmt.Errorf("metrics buffer is full (%d entries); newest metric was not queued", q.capacity)
	}
	entries := append(append([]wsclient.AgentMessage(nil), q.entries...), message)
	if err := q.persist(entries); err != nil {
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
	if err := q.persist(entries); err != nil {
		return false, err
	}
	q.entries = entries
	return true, nil
}

// Len returns the current number of unacknowledged frames.
func (q *Queue) Len() int {
	q.mu.Lock()
	defer q.mu.Unlock()
	return len(q.entries)
}

func (q *Queue) persist(entries []wsclient.AgentMessage) error {
	data, err := json.Marshal(snapshot{Version: snapshotVersion, ServerID: q.serverID, Entries: entries})
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
