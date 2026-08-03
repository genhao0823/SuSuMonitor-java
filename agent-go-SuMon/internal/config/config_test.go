package config

import (
	"path/filepath"
	"testing"
)

func setValidEnvironment(t *testing.T) {
	t.Helper()
	t.Setenv("SUSUMONITOR_BACKEND_URL", "ws://localhost:18080")
	t.Setenv("SUSUMONITOR_SERVER_ID", "42")
	t.Setenv("SUSUMONITOR_AGENT_TOKEN", "test-agent-token")
	t.Setenv("SUSUMONITOR_COLLECT_INTERVAL_SECONDS", "7")
	t.Setenv("SUSUMONITOR_HEARTBEAT_INTERVAL_SECONDS", "30")
	t.Setenv("SUSUMONITOR_RECONNECT_INITIAL_SECONDS", "5")
	t.Setenv("SUSUMONITOR_RECONNECT_MAX_SECONDS", "60")
	t.Setenv("SUSUMONITOR_METRICS_BUFFER_PATH", filepath.Join(t.TempDir(), "metrics-buffer.json"))
	t.Setenv("SUSUMONITOR_METRICS_BUFFER_MAX_ENTRIES", "720")
	t.Setenv("SUSUMONITOR_METRICS_ACK_TIMEOUT_SECONDS", "15")
	t.Setenv("SUSUMONITOR_METRICS_RETRY_INITIAL_SECONDS", "2")
	t.Setenv("SUSUMONITOR_METRICS_RETRY_MAX_SECONDS", "60")
	t.Setenv("SUSUMONITOR_METRICS_REPLAY_MIN_INTERVAL_MILLIS", "2500")
	t.Setenv("SUSUMONITOR_LOG_LEVEL", "debug")
	t.Setenv("SUSUMONITOR_TERMINAL_ENABLED", "false")
	t.Setenv("SUSUMONITOR_TERMINAL_SHELL", "/bin/bash")
	t.Setenv("SUSUMONITOR_TERMINAL_MAX_SESSIONS", "4")
	t.Setenv("SUSUMONITOR_TERMINAL_MAX_INPUT_BYTES", "16384")
	t.Setenv("SUSUMONITOR_TERMINAL_MAX_OUTPUT_BYTES", "16384")
	t.Setenv("SUSUMONITOR_TERMINAL_OUTPUT_RATE_BYTES_PER_SECOND", "262144")
	t.Setenv("SUSUMONITOR_TERMINAL_OUTPUT_BURST_BYTES", "524288")
	t.Setenv("SUSUMONITOR_TERMINAL_OUTPUT_QUEUE_SIZE", "64")
	t.Setenv("SUSUMONITOR_TERMINAL_IDLE_TIMEOUT_SECONDS", "1200")
	t.Setenv("SUSUMONITOR_TERMINAL_MAX_LIFETIME_SECONDS", "28800")
}

// TestLoadValidConfig 验证合法环境变量能完整加载并覆盖默认值。
func TestLoadValidConfig(t *testing.T) {
	setValidEnvironment(t)
	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if cfg.ServerID != 42 || cfg.CollectIntervalSeconds != 7 || cfg.LogLevel != "debug" ||
		cfg.TerminalOutputRateBytesPerSecond != 256*1024 || cfg.TerminalOutputBurstBytes != 512*1024 {
		t.Fatalf("unexpected config: %+v", cfg)
	}
}

func TestLoadUsesTerminalOutputRateDefaults(t *testing.T) {
	setValidEnvironment(t)
	t.Setenv("SUSUMONITOR_TERMINAL_OUTPUT_RATE_BYTES_PER_SECOND", "")
	t.Setenv("SUSUMONITOR_TERMINAL_OUTPUT_BURST_BYTES", "")
	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if cfg.TerminalOutputRateBytesPerSecond != 256*1024 || cfg.TerminalOutputBurstBytes != 512*1024 {
		t.Fatalf("unexpected terminal output defaults: %+v", cfg)
	}
}

func TestLoadRejectsInvalidInteger(t *testing.T) {
	setValidEnvironment(t)
	t.Setenv("SUSUMONITOR_HEARTBEAT_INTERVAL_SECONDS", "not-a-number")
	if _, err := Load(); err == nil {
		t.Fatal("Load() error = nil, want invalid integer error")
	}
}

func TestLoadRejectsInvalidValues(t *testing.T) {
	tests := []struct {
		name  string
		key   string
		value string
	}{
		{"missing backend URL", "SUSUMONITOR_BACKEND_URL", ""},
		{"invalid backend URL", "SUSUMONITOR_BACKEND_URL", "http://localhost:18080"},
		{"missing token", "SUSUMONITOR_AGENT_TOKEN", ""},
		{"zero collect interval", "SUSUMONITOR_COLLECT_INTERVAL_SECONDS", "0"},
		{"zero heartbeat interval", "SUSUMONITOR_HEARTBEAT_INTERVAL_SECONDS", "0"},
		{"zero reconnect initial", "SUSUMONITOR_RECONNECT_INITIAL_SECONDS", "0"},
		{"metrics buffer relative path", "SUSUMONITOR_METRICS_BUFFER_PATH", "metrics-buffer.json"},
		{"metrics buffer capacity zero", "SUSUMONITOR_METRICS_BUFFER_MAX_ENTRIES", "0"},
		{"metrics acknowledgement timeout zero", "SUSUMONITOR_METRICS_ACK_TIMEOUT_SECONDS", "0"},
		{"metrics retry initial zero", "SUSUMONITOR_METRICS_RETRY_INITIAL_SECONDS", "0"},
		{"metrics retry max below initial", "SUSUMONITOR_METRICS_RETRY_MAX_SECONDS", "1"},
		{"metrics replay interval too large", "SUSUMONITOR_METRICS_REPLAY_MIN_INTERVAL_MILLIS", "60001"},
		{"terminal sessions too large", "SUSUMONITOR_TERMINAL_MAX_SESSIONS", "5"},
		{"terminal input too large", "SUSUMONITOR_TERMINAL_MAX_INPUT_BYTES", "16385"},
		{"terminal output rate zero", "SUSUMONITOR_TERMINAL_OUTPUT_RATE_BYTES_PER_SECOND", "0"},
		{"terminal output burst below output block", "SUSUMONITOR_TERMINAL_OUTPUT_BURST_BYTES", "16383"},
		{"terminal relative shell", "SUSUMONITOR_TERMINAL_SHELL", "bash"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			setValidEnvironment(t)
			if tt.key == "SUSUMONITOR_TERMINAL_SHELL" {
				t.Setenv("SUSUMONITOR_TERMINAL_ENABLED", "true")
			}
			t.Setenv(tt.key, tt.value)
			if _, err := Load(); err == nil {
				t.Fatal("Load() error = nil, want validation error")
			}
		})
	}
}

func TestLoadRejectsReconnectMaxBelowInitial(t *testing.T) {
	setValidEnvironment(t)
	t.Setenv("SUSUMONITOR_RECONNECT_INITIAL_SECONDS", "10")
	t.Setenv("SUSUMONITOR_RECONNECT_MAX_SECONDS", "5")
	if _, err := Load(); err == nil {
		t.Fatal("Load() error = nil, want reconnect range error")
	}
}
