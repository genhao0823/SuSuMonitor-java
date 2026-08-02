// Package reporter 构造并发送 metrics.report 消息。
package reporter

import (
	"fmt"
	"log/slog"
	"time"

	"agent-go-SuMon/internal/collector"
	"agent-go-SuMon/internal/wsclient"
)

// MetricsSender 定义 Reporter 上报 metrics.report 所需的最小发送能力。
type MetricsSender interface {
	SendMetrics(payload wsclient.MetricsPayload) error
}

// Reporter 负责将采集的指标构造为 metrics.report 消息并通过连接发送。
type Reporter struct {
	serverID int64
	logger   *slog.Logger
	sender   MetricsSender
}

// NewReporter 创建 Reporter。
func NewReporter(serverID int64, logger *slog.Logger, sender MetricsSender) *Reporter {
	return &Reporter{
		serverID: serverID,
		logger:   logger,
		sender:   sender,
	}
}

// Report 将采集的指标构造为 metrics.report 消息并发送。
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
	if err := r.sender.SendMetrics(payload); err != nil {
		return fmt.Errorf("send metrics: %w", err)
	}
	r.logger.Debug("metrics reported", "server_id", r.serverID, "collected_at", payload.CollectedAt)
	return nil
}
