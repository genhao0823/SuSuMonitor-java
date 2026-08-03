// Package config 定义 SuSuMonitor Agent 的运行时配置。
//
// 当前正式运行方式仅从环境变量加载；配置文件示例用于部署参考，不会被自动读取。
package config

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// Config 是 Agent 的运行时配置。
type Config struct {
	// BackendURL 是后端 WebSocket 地址，如 ws://localhost:18080。
	BackendURL string
	// ServerID 是 admin 预建的服务器 ID。
	ServerID int64
	// AgentToken 是 admin 通过 REST 预发放的 Agent Token，明文仅一次性返回。
	AgentToken string
	// CollectIntervalSeconds 是指标采集间隔，默认 5 秒。
	CollectIntervalSeconds int
	// HeartbeatIntervalSeconds 是心跳间隔，默认 30 秒。
	HeartbeatIntervalSeconds int
	// ReconnectInitialSeconds 是重连初始间隔，默认 5 秒。
	ReconnectInitialSeconds int
	// ReconnectMaxSeconds 是重连最大间隔，默认 60 秒。
	ReconnectMaxSeconds int
	// MetricsBufferPath 是未确认指标的持久化 FIFO 文件路径。
	MetricsBufferPath string
	// MetricsBufferMaxEntries 是未确认指标的最大条数，默认 720。
	MetricsBufferMaxEntries int
	// MetricsAckTimeoutSeconds 是一次指标写入后等待服务端确认的最长时间。
	MetricsAckTimeoutSeconds int
	// MetricsRetryInitialSeconds 是确认超时后的首次重传等待时间。
	MetricsRetryInitialSeconds int
	// MetricsRetryMaxSeconds 是确认超时重传的最大等待时间。
	MetricsRetryMaxSeconds int
	// MetricsReplayMinIntervalMillis 是积压 FIFO 相邻重放之间的最小间隔。
	MetricsReplayMinIntervalMillis int
	// LogLevel 是日志级别，如 info、debug。
	LogLevel string
	// TerminalEnabled 控制是否接受远程终端协议消息，默认关闭。
	TerminalEnabled bool
	// TerminalShell 是 Linux PTY 固定启动的 Shell，远端不得覆盖。
	TerminalShell string
	// TerminalMaxSessions 是 Agent 本地 PTY 会话上限。
	TerminalMaxSessions int
	// TerminalMaxInputBytes 是单个 Base64 解码后输入块的最大字节数。
	TerminalMaxInputBytes int
	// TerminalMaxOutputBytes 是单个 PTY 输出块的最大字节数。
	TerminalMaxOutputBytes int
	// TerminalOutputRateBytesPerSecond 是单个 PTY 会话每秒允许转发的原始输出字节数。
	TerminalOutputRateBytesPerSecond int
	// TerminalOutputBurstBytes 是单个 PTY 会话可立即转发的原始输出字节突发上限。
	TerminalOutputBurstBytes int
	// TerminalOutputQueueSize 是每个 PTY 会话的有界输出队列大小。
	TerminalOutputQueueSize int
	// TerminalIdleTimeoutSeconds 是无输入自动关闭阈值。
	TerminalIdleTimeoutSeconds int
	// TerminalMaxLifetimeSeconds 是单会话最大生命周期。
	TerminalMaxLifetimeSeconds int
}

// Load 从环境变量加载配置并校验。
//
// 期望的环境变量：
//   - SUSUMONITOR_BACKEND_URL
//   - SUSUMONITOR_SERVER_ID
//   - SUSUMONITOR_AGENT_TOKEN
//   - SUSUMONITOR_COLLECT_INTERVAL_SECONDS
//   - SUSUMONITOR_HEARTBEAT_INTERVAL_SECONDS
//   - SUSUMONITOR_RECONNECT_INITIAL_SECONDS
//   - SUSUMONITOR_RECONNECT_MAX_SECONDS
//   - SUSUMONITOR_LOG_LEVEL
func Load() (*Config, error) {
	cfg := &Config{
		BackendURL:        os.Getenv("SUSUMONITOR_BACKEND_URL"),
		AgentToken:        os.Getenv("SUSUMONITOR_AGENT_TOKEN"),
		MetricsBufferPath: getenvDefault("SUSUMONITOR_METRICS_BUFFER_PATH", "/var/lib/susumonitor/metrics-buffer.json"),
		LogLevel:          getenvDefault("SUSUMONITOR_LOG_LEVEL", "info"),
		TerminalShell:     getenvDefault("SUSUMONITOR_TERMINAL_SHELL", "/bin/bash"),
	}

	var err error
	if cfg.CollectIntervalSeconds, err = getenvIntDefault("SUSUMONITOR_COLLECT_INTERVAL_SECONDS", 5); err != nil {
		return nil, err
	}
	if cfg.HeartbeatIntervalSeconds, err = getenvIntDefault("SUSUMONITOR_HEARTBEAT_INTERVAL_SECONDS", 30); err != nil {
		return nil, err
	}
	if cfg.ReconnectInitialSeconds, err = getenvIntDefault("SUSUMONITOR_RECONNECT_INITIAL_SECONDS", 5); err != nil {
		return nil, err
	}
	if cfg.ReconnectMaxSeconds, err = getenvIntDefault("SUSUMONITOR_RECONNECT_MAX_SECONDS", 60); err != nil {
		return nil, err
	}
	if cfg.MetricsBufferMaxEntries, err = getenvIntDefault("SUSUMONITOR_METRICS_BUFFER_MAX_ENTRIES", 720); err != nil {
		return nil, err
	}
	if cfg.MetricsAckTimeoutSeconds, err = getenvIntDefault("SUSUMONITOR_METRICS_ACK_TIMEOUT_SECONDS", 15); err != nil {
		return nil, err
	}
	if cfg.MetricsRetryInitialSeconds, err = getenvIntDefault("SUSUMONITOR_METRICS_RETRY_INITIAL_SECONDS", 2); err != nil {
		return nil, err
	}
	if cfg.MetricsRetryMaxSeconds, err = getenvIntDefault("SUSUMONITOR_METRICS_RETRY_MAX_SECONDS", 60); err != nil {
		return nil, err
	}
	if cfg.MetricsReplayMinIntervalMillis, err = getenvIntDefault("SUSUMONITOR_METRICS_REPLAY_MIN_INTERVAL_MILLIS", 2500); err != nil {
		return nil, err
	}
	if cfg.TerminalEnabled, err = getenvBoolDefault("SUSUMONITOR_TERMINAL_ENABLED", false); err != nil {
		return nil, err
	}
	if cfg.TerminalMaxSessions, err = getenvIntDefault("SUSUMONITOR_TERMINAL_MAX_SESSIONS", 4); err != nil {
		return nil, err
	}
	if cfg.TerminalMaxInputBytes, err = getenvIntDefault("SUSUMONITOR_TERMINAL_MAX_INPUT_BYTES", 16*1024); err != nil {
		return nil, err
	}
	if cfg.TerminalMaxOutputBytes, err = getenvIntDefault("SUSUMONITOR_TERMINAL_MAX_OUTPUT_BYTES", 16*1024); err != nil {
		return nil, err
	}
	if cfg.TerminalOutputRateBytesPerSecond, err = getenvIntDefault("SUSUMONITOR_TERMINAL_OUTPUT_RATE_BYTES_PER_SECOND", 256*1024); err != nil {
		return nil, err
	}
	if cfg.TerminalOutputBurstBytes, err = getenvIntDefault("SUSUMONITOR_TERMINAL_OUTPUT_BURST_BYTES", 512*1024); err != nil {
		return nil, err
	}
	if cfg.TerminalOutputQueueSize, err = getenvIntDefault("SUSUMONITOR_TERMINAL_OUTPUT_QUEUE_SIZE", 64); err != nil {
		return nil, err
	}
	if cfg.TerminalIdleTimeoutSeconds, err = getenvIntDefault("SUSUMONITOR_TERMINAL_IDLE_TIMEOUT_SECONDS", 20*60); err != nil {
		return nil, err
	}
	if cfg.TerminalMaxLifetimeSeconds, err = getenvIntDefault("SUSUMONITOR_TERMINAL_MAX_LIFETIME_SECONDS", 8*60*60); err != nil {
		return nil, err
	}

	serverID, err := getenvInt64("SUSUMONITOR_SERVER_ID")
	if err != nil {
		return nil, fmt.Errorf("server_id: %w", err)
	}
	cfg.ServerID = serverID

	if err := cfg.validate(); err != nil {
		return nil, err
	}
	return cfg, nil
}

// validate 校验配置项的合法性，非法配置启动失败。
func (c *Config) validate() error {
	if c.BackendURL == "" {
		return fmt.Errorf("SUSUMONITOR_BACKEND_URL is required")
	}
	if !strings.HasPrefix(c.BackendURL, "ws://") && !strings.HasPrefix(c.BackendURL, "wss://") {
		return fmt.Errorf("SUSUMONITOR_BACKEND_URL must start with ws:// or wss://")
	}
	if c.AgentToken == "" {
		return fmt.Errorf("SUSUMONITOR_AGENT_TOKEN is required")
	}
	if c.CollectIntervalSeconds < 1 {
		return fmt.Errorf("collect interval must be >= 1, got %d", c.CollectIntervalSeconds)
	}
	if c.HeartbeatIntervalSeconds < 1 {
		return fmt.Errorf("heartbeat interval must be >= 1, got %d", c.HeartbeatIntervalSeconds)
	}
	if c.ReconnectInitialSeconds < 1 {
		return fmt.Errorf("reconnect initial interval must be >= 1, got %d", c.ReconnectInitialSeconds)
	}
	if c.ReconnectMaxSeconds < c.ReconnectInitialSeconds {
		return fmt.Errorf("reconnect max (%d) must be >= initial (%d)",
			c.ReconnectMaxSeconds, c.ReconnectInitialSeconds)
	}
	if !filepath.IsAbs(c.MetricsBufferPath) || filepath.Clean(c.MetricsBufferPath) != c.MetricsBufferPath {
		return fmt.Errorf("SUSUMONITOR_METRICS_BUFFER_PATH must be a clean absolute path")
	}
	if c.MetricsBufferMaxEntries < 1 || c.MetricsBufferMaxEntries > 100000 {
		return fmt.Errorf("metrics buffer max entries must be between 1 and 100000")
	}
	if c.MetricsAckTimeoutSeconds < 1 || c.MetricsAckTimeoutSeconds > 300 {
		return fmt.Errorf("metrics acknowledgement timeout must be between 1 and 300 seconds")
	}
	if c.MetricsRetryInitialSeconds < 1 {
		return fmt.Errorf("metrics retry initial seconds must be positive")
	}
	if c.MetricsRetryMaxSeconds < c.MetricsRetryInitialSeconds {
		return fmt.Errorf("metrics retry max (%d) must be >= initial (%d)",
			c.MetricsRetryMaxSeconds, c.MetricsRetryInitialSeconds)
	}
	if c.MetricsReplayMinIntervalMillis < 0 || c.MetricsReplayMinIntervalMillis > 60000 {
		return fmt.Errorf("metrics replay minimum interval must be between 0 and 60000 milliseconds")
	}
	if c.TerminalMaxSessions < 1 || c.TerminalMaxSessions > 4 {
		return fmt.Errorf("terminal max sessions must be between 1 and 4")
	}
	if c.TerminalMaxInputBytes < 1 || c.TerminalMaxInputBytes > 16*1024 {
		return fmt.Errorf("terminal max input bytes must be between 1 and 16384")
	}
	if c.TerminalMaxOutputBytes < 1 || c.TerminalMaxOutputBytes > 16*1024 {
		return fmt.Errorf("terminal max output bytes must be between 1 and 16384")
	}
	if c.TerminalOutputRateBytesPerSecond < 1 {
		return fmt.Errorf("terminal output rate bytes per second must be positive")
	}
	if c.TerminalOutputBurstBytes < c.TerminalMaxOutputBytes {
		return fmt.Errorf("terminal output burst bytes (%d) must be >= max output bytes (%d)",
			c.TerminalOutputBurstBytes, c.TerminalMaxOutputBytes)
	}
	if c.TerminalOutputQueueSize < 1 || c.TerminalOutputQueueSize > 64 {
		return fmt.Errorf("terminal output queue size must be between 1 and 64")
	}
	if c.TerminalIdleTimeoutSeconds < 1 || c.TerminalMaxLifetimeSeconds < 1 {
		return fmt.Errorf("terminal timeouts must be positive")
	}
	if c.TerminalEnabled && (!filepath.IsAbs(c.TerminalShell) || filepath.Clean(c.TerminalShell) != c.TerminalShell) {
		return fmt.Errorf("SUSUMONITOR_TERMINAL_SHELL must be a clean absolute path")
	}
	return nil
}

// getenvBoolDefault 读取严格的布尔环境变量。
func getenvBoolDefault(key string, def bool) (bool, error) {
	v := os.Getenv(key)
	if v == "" {
		return def, nil
	}
	parsed, err := strconv.ParseBool(v)
	if err != nil {
		return false, fmt.Errorf("%s must be a boolean, got %q", key, v)
	}
	return parsed, nil
}

// getenvDefault 读取环境变量，为空时返回默认值。
func getenvDefault(key, def string) string {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	return v
}

// getenvIntDefault 读取环境变量为 int，未设置时返回默认值，设置但无法解析时返回错误。
//
// 区分“缺省”与“非法值”：缺省使用默认值保证开箱即用；非法值显式报错，
// 避免 Agent 因配置拼写错误而静默回退默认值，导致启动行为不符合预期。
func getenvIntDefault(key string, def int) (int, error) {
	v := os.Getenv(key)
	if v == "" {
		return def, nil
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return 0, fmt.Errorf("%s must be an integer, got %q", key, v)
	}
	return n, nil
}

// getenvInt64 读取环境变量为 int64，为空或非法时返回错误。
func getenvInt64(key string) (int64, error) {
	v := os.Getenv(key)
	if v == "" {
		return 0, fmt.Errorf("%s is required", key)
	}
	n, err := strconv.ParseInt(v, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("%s must be an integer: %s", key, v)
	}
	if n <= 0 {
		return 0, fmt.Errorf("%s must be positive: %d", key, n)
	}
	return n, nil
}
