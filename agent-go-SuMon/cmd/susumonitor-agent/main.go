// Package main 是 SuSuMonitor Agent 的启动入口。
//
// 加载配置并启动 WebSocket、指标采集和指标上报。
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
	"time"

	"agent-go-SuMon/internal/collector"
	"agent-go-SuMon/internal/config"
	"agent-go-SuMon/internal/metricbuffer"
	"agent-go-SuMon/internal/reporter"
	"agent-go-SuMon/internal/wsclient"
)

// main 加载配置并运行 Agent，配置错误或连接运行异常时以非零状态退出。
func main() {
	cfg, err := config.Load()
	if err != nil {
		fmt.Fprintf(os.Stderr, "config load failed: %v\n", err)
		os.Exit(1)
	}

	logger := newLogger(cfg.LogLevel)
	logger.Info("susumonitor agent starting",
		"backend_url", cfg.BackendURL,
		"server_id", cfg.ServerID,
		"collect_interval", cfg.CollectIntervalSeconds,
		"heartbeat_interval", cfg.HeartbeatIntervalSeconds,
	)

	client := wsclient.NewClient(
		cfg.BackendURL,
		cfg.ServerID,
		cfg.AgentToken,
		logger,
		time.Duration(cfg.HeartbeatIntervalSeconds)*time.Second,
		time.Duration(cfg.ReconnectInitialSeconds)*time.Second,
		time.Duration(cfg.ReconnectMaxSeconds)*time.Second,
	)
	terminalAgent, err := newTerminalAgent(cfg, client, logger)
	if err != nil {
		logger.Error("terminal initialization failed", "error", err)
		os.Exit(1)
	}
	metricsBuffer, err := metricbuffer.Open(cfg.MetricsBufferPath, cfg.ServerID, cfg.MetricsBufferMaxEntries)
	if err != nil {
		logger.Error("metrics buffer initialization failed", "error", err)
		os.Exit(1)
	}
	metricsReporter := reporter.NewReporter(cfg.ServerID, logger, client, metricsBuffer, reporter.Options{
		AckTimeout:        time.Duration(cfg.MetricsAckTimeoutSeconds) * time.Second,
		RetryInitial:      time.Duration(cfg.MetricsRetryInitialSeconds) * time.Second,
		RetryMax:          time.Duration(cfg.MetricsRetryMaxSeconds) * time.Second,
		RetryJitter:       cfg.MetricsRetryJitterEnabled,
		ReplayMinInterval: time.Duration(cfg.MetricsReplayMinIntervalMillis) * time.Millisecond,
	})
	client.SetMessageHandler(terminalAgent.handle)
	client.SetMetricsAckHandler(metricsReporter.HandleMetricsAck)
	client.SetAuthenticatedHandler(metricsReporter.HandleAuthenticated)
	client.SetDisconnectHandler(func() {
		metricsReporter.HandleDisconnect()
		terminalAgent.manager.CloseAll("agent_disconnected")
	})

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	if err := runWithDependencies(ctx, cfg, logger, client, collector.NewGopsutilCollector(), metricsReporter); err != nil {
		logger.Error("agent exited with error", "error", err)
		os.Exit(1)
	}
	terminalAgent.manager.CloseAll("agent_shutdown")
	logger.Info("agent shutdown complete")
}

// metricsReporter 定义 Agent 运行循环所需的最小指标上报能力。
type metricsReporter interface {
	Report(collector.Metrics) error
}

// run 在同一可取消生命周期内运行 WebSocket、指标采集和上报。
func run(ctx context.Context, cfg *config.Config, logger *slog.Logger, client *wsclient.Client) error {
	metricsBuffer, err := metricbuffer.Open(cfg.MetricsBufferPath, cfg.ServerID, cfg.MetricsBufferMaxEntries)
	if err != nil {
		return fmt.Errorf("open metrics buffer: %w", err)
	}
	return runWithDependencies(ctx, cfg, logger, client, collector.NewGopsutilCollector(),
		reporter.NewReporter(cfg.ServerID, logger, client, metricsBuffer, reporter.Options{
			AckTimeout:   time.Duration(cfg.MetricsAckTimeoutSeconds) * time.Second,
			RetryInitial: time.Duration(cfg.MetricsRetryInitialSeconds) * time.Second,
			RetryMax:     time.Duration(cfg.MetricsRetryMaxSeconds) * time.Second,
		}))
}

// runWithDependencies 允许测试替换采集器和上报器，生产环境由 run 注入真实实现。
func runWithDependencies(ctx context.Context, cfg *config.Config, logger *slog.Logger, client *wsclient.Client,
	metricsCollector collector.Collector, metricsReporter metricsReporter) error {
	collectTicker := time.NewTicker(time.Duration(cfg.CollectIntervalSeconds) * time.Second)
	defer collectTicker.Stop()

	clientErrCh := make(chan error, 1)
	go func() {
		clientErrCh <- client.Run(ctx)
	}()

	reportMetrics(metricsCollector, metricsReporter, logger)
	for {
		select {
		case <-ctx.Done():
			err := <-clientErrCh
			if errors.Is(err, context.Canceled) {
				return nil
			}
			return err
		case err := <-clientErrCh:
			if errors.Is(err, context.Canceled) {
				return nil
			}
			return err
		case <-collectTicker.C:
			reportMetrics(metricsCollector, metricsReporter, logger)
		}
	}
}

// reportMetrics 采集并尽力上报一次指标；采集或发送失败不会停止 Agent。
func reportMetrics(metricsCollector collector.Collector, metricsReporter metricsReporter, logger *slog.Logger) {
	metrics, err := metricsCollector.Collect()
	if err != nil {
		logger.Warn("metrics collection failed", "error", err)
		return
	}
	if err := metricsReporter.Report(metrics); err != nil {
		logger.Warn("metrics report failed", "error", err)
	}
}

// newLogger 创建结构化日志器，输出 JSON 到 stdout。
func newLogger(level string) *slog.Logger {
	var lvl slog.Level
	switch level {
	case "debug":
		lvl = slog.LevelDebug
	case "warn":
		lvl = slog.LevelWarn
	case "error":
		lvl = slog.LevelError
	default:
		lvl = slog.LevelInfo
	}
	handler := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: lvl})
	return slog.New(handler)
}
