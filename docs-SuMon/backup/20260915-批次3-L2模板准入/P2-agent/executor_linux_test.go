//go:build linux

package command

import (
	"context"
	"os/exec"
	"regexp"
	"strings"
	"sync"
	"testing"
	"time"
)

// testTemplatesOnce 保证测试模板只注册一次；仅测试进程使用，不影响生产白名单。
var testTemplatesOnce sync.Once

// registerTestTemplates 向内建表注入执行行为可控的测试模板：
// echo 原样回显参数、sleeper 挂起 30 秒（测超时）、flood 输出超限字节、
// leaker 输出凭据样式（测脱敏）。测试模板同样遵守"无 shell"原则。
func registerTestTemplates(t *testing.T) {
	t.Helper()
	testTemplatesOnce.Do(func() {
		templates["echo"] = Template{ID: "echo", Argv: []string{"echo", "{message}"},
			Params: []ParamSpec{{Name: "message", Pattern: regexp.MustCompile(`^.{0,64}$`)}}}
		templates["sleeper"] = Template{ID: "sleeper", Argv: []string{"sleep", "30"}}
		templates["flood"] = Template{ID: "flood",
			Argv: []string{"/usr/bin/dd", "if=/dev/zero", "bs=1024", "count=4"}}
		templates["leaker"] = Template{ID: "leaker",
			Argv: []string{"/usr/bin/printf", "password=hunter2\n"}}
	})
	if _, err := exec.LookPath("sleep"); err != nil {
		t.Skip("sleep not available on this platform")
	}
}

// TestExecuteRunsWhitelistedCommand 验证白名单命令真实执行并回传输出与退出码。
func TestExecuteRunsWhitelistedCommand(t *testing.T) {
	registerTestTemplates(t)
	executor := NewExecutor(Config{Enabled: true, MaxTimeout: 10 * time.Second,
		MaxOutputBytes: 64 * 1024, RatePerMinute: 10})
	result := executor.Execute(context.Background(), "echo", map[string]string{"message": "hello"}, 5)
	if !result.Success || result.ExitCode != 0 {
		t.Fatalf("echo should succeed, got %+v", result)
	}
	if !strings.Contains(result.Stdout, "hello") {
		t.Fatalf("stdout should contain command output, got %q", result.Stdout)
	}
	if result.Error != "" {
		t.Fatalf("success result must not carry error, got %q", result.Error)
	}
}

// TestExecuteRejectsUnknownTemplate 验证执行路径拒绝未知模板并返回稳定原因。
func TestExecuteRejectsUnknownTemplate(t *testing.T) {
	registerTestTemplates(t)
	executor := NewExecutor(Config{Enabled: true, MaxTimeout: 10 * time.Second,
		MaxOutputBytes: 64 * 1024, RatePerMinute: 10})
	result := executor.Execute(context.Background(), "no_such_template", nil, 5)
	if result.Success || result.Error != ErrReasonTemplateUnknown {
		t.Fatalf("unknown template must fail with %s, got %+v", ErrReasonTemplateUnknown, result)
	}
}

// TestExecuteDisabledReturnsUnsupported 验证关闭开关时不执行任何命令。
func TestExecuteDisabledReturnsUnsupported(t *testing.T) {
	registerTestTemplates(t)
	executor := NewExecutor(Config{Enabled: false, MaxTimeout: 10 * time.Second,
		MaxOutputBytes: 64 * 1024, RatePerMinute: 10})
	result := executor.Execute(context.Background(), "echo", map[string]string{"message": "hello"}, 5)
	if result.Success || result.Error != ErrReasonUnsupportedPlatform {
		t.Fatalf("disabled executor must return unsupported, got %+v", result)
	}
}

// TestExecuteTimeoutKillsCommand 验证超时强制终止并返回 timeout 原因。
func TestExecuteTimeoutKillsCommand(t *testing.T) {
	registerTestTemplates(t)
	executor := NewExecutor(Config{Enabled: true, MaxTimeout: time.Second,
		MaxOutputBytes: 64 * 1024, RatePerMinute: 10})
	result := executor.Execute(context.Background(), "sleeper", nil, 1)
	if result.Success || result.Error != ErrReasonTimeout {
		t.Fatalf("timeout must be reported, got %+v", result)
	}
	if result.DurationMs > 3000 {
		t.Fatalf("timeout should kill near the deadline, took %dms", result.DurationMs)
	}
}

// TestExecuteTruncatesOversizedOutput 验证超限输出被截断并置 truncated 标志。
func TestExecuteTruncatesOversizedOutput(t *testing.T) {
	registerTestTemplates(t)
	executor := NewExecutor(Config{Enabled: true, MaxTimeout: 10 * time.Second,
		MaxOutputBytes: 1024, RatePerMinute: 10})
	result := executor.Execute(context.Background(), "flood", nil, 5)
	if !result.Truncated {
		t.Fatalf("oversized output must be truncated, got %+v", result)
	}
	if len(result.Stdout) > 1024 {
		t.Fatalf("stdout must be capped at 1024, got %d", len(result.Stdout))
	}
}

// TestExecuteRedactsCredentials 验证凭据样式输出被脱敏。
func TestExecuteRedactsCredentials(t *testing.T) {
	registerTestTemplates(t)
	executor := NewExecutor(Config{Enabled: true, MaxTimeout: 10 * time.Second,
		MaxOutputBytes: 64 * 1024, RatePerMinute: 10})
	result := executor.Execute(context.Background(), "leaker", nil, 5)
	if strings.Contains(result.Stdout, "hunter2") {
		t.Fatalf("password value must be redacted, got %q", result.Stdout)
	}
	if !strings.Contains(result.Stdout, "[REDACTED]") {
		t.Fatalf("redaction placeholder expected, got %q", result.Stdout)
	}
}

// TestExecuteRateLimit 验证本机固定窗口频次限制。
func TestExecuteRateLimit(t *testing.T) {
	registerTestTemplates(t)
	executor := NewExecutor(Config{Enabled: true, MaxTimeout: 10 * time.Second,
		MaxOutputBytes: 64 * 1024, RatePerMinute: 2})
	executor.Execute(context.Background(), "echo", map[string]string{"message": "1"}, 5)
	executor.Execute(context.Background(), "echo", map[string]string{"message": "2"}, 5)
	result := executor.Execute(context.Background(), "echo", map[string]string{"message": "3"}, 5)
	if result.Success || result.Error != ErrReasonRateLimited {
		t.Fatalf("third call within window must be rate limited, got %+v", result)
	}
}
