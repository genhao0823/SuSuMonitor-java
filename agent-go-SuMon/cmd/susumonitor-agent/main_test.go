package main

import (
	"errors"
	"io"
	"log/slog"
	"testing"
	"time"

	"agent-go-SuMon/internal/collector"
	"agent-go-SuMon/internal/config"
)

type recordingCollector struct {
	metrics collector.Metrics
	err     error
	calls   int
}

func (c *recordingCollector) Collect() (collector.Metrics, error) {
	c.calls++
	return c.metrics, c.err
}

type recordingReporter struct {
	metrics collector.Metrics
	err     error
	calls   int
}

func (r *recordingReporter) Report(metrics collector.Metrics) error {
	r.calls++
	r.metrics = metrics
	return r.err
}

func TestReporterOptionsMapsAllReliableDeliverySettings(t *testing.T) {
	options := newReporterOptions(&config.Config{
		MetricsAckTimeoutSeconds:       15,
		MetricsRetryInitialSeconds:     2,
		MetricsRetryMaxSeconds:         60,
		MetricsRetryJitterEnabled:      true,
		MetricsReplayMinIntervalMillis: 2500,
	})
	if options.AckTimeout != 15*time.Second || options.RetryInitial != 2*time.Second ||
		options.RetryMax != 60*time.Second || !options.RetryJitter ||
		options.ReplayMinInterval != 2500*time.Millisecond {
		t.Fatalf("unexpected reporter options: %+v", options)
	}
}

func TestReportMetricsReportsCollectedSnapshot(t *testing.T) {
	cpu := 12.5
	metrics := collector.Metrics{CPUPercent: &cpu}
	metricsCollector := &recordingCollector{metrics: metrics}
	metricsReporter := &recordingReporter{}

	reportMetrics(metricsCollector, metricsReporter, slog.New(slog.NewTextHandler(io.Discard, nil)))

	if metricsCollector.calls != 1 || metricsReporter.calls != 1 {
		t.Fatalf("calls: collector=%d reporter=%d, want 1 each", metricsCollector.calls, metricsReporter.calls)
	}
	if metricsReporter.metrics.CPUPercent == nil || *metricsReporter.metrics.CPUPercent != cpu {
		t.Fatalf("reported metrics = %+v, want cpu %.1f", metricsReporter.metrics, cpu)
	}
}

func TestReportMetricsDoesNotReportCollectionFailure(t *testing.T) {
	metricsCollector := &recordingCollector{err: errors.New("collect failed")}
	metricsReporter := &recordingReporter{}

	reportMetrics(metricsCollector, metricsReporter, slog.New(slog.NewTextHandler(io.Discard, nil)))

	if metricsCollector.calls != 1 || metricsReporter.calls != 0 {
		t.Fatalf("calls: collector=%d reporter=%d, want 1 and 0", metricsCollector.calls, metricsReporter.calls)
	}
}

func TestReportMetricsContinuesAfterReportFailure(t *testing.T) {
	metricsCollector := &recordingCollector{}
	metricsReporter := &recordingReporter{err: errors.New("send failed")}

	reportMetrics(metricsCollector, metricsReporter, slog.New(slog.NewTextHandler(io.Discard, nil)))

	if metricsCollector.calls != 1 || metricsReporter.calls != 1 {
		t.Fatalf("calls: collector=%d reporter=%d, want 1 each", metricsCollector.calls, metricsReporter.calls)
	}
}
