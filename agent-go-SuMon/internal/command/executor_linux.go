//go:build linux

package command

import (
	"bytes"
	"context"
	"errors"
	"os/exec"
	"regexp"
	"sync"
	"time"
)

// rateLimiter 是 Agent 本机的固定窗口命令频次限制（每分钟允许 carryPermit 次执行）。
type rateLimiter struct {
	mu        sync.Mutex
	window    time.Time
	count     int
	perMinute int
}

// newRateLimiter 创建固定窗口限流器。
func newRateLimiter(perMinute int) *rateLimiter {
	return &rateLimiter{perMinute: perMinute, window: time.Now()}
}

// allow 判断当前窗口内是否还允许一次执行，并消耗额度。
func (r *rateLimiter) allow() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	now := time.Now()
	if now.Sub(r.window) >= time.Minute {
		r.window = now
		r.count = 0
	}
	if r.count >= r.perMinute {
		return false
	}
	r.count++
	return true
}

// 敏感信息脱敏规则：仅替换高置信凭据样式，避免误伤正常运维输出。
var (
	patternKVSecret    = regexp.MustCompile(`(?i)\b(password|passwd|secret|token|api[_-]?key|access[_-]?key)(\s*[:=]\s*)\S+`)
	patternBearer      = regexp.MustCompile(`(?i)\b(bearer|basic)\s+[a-zA-Z0-9._~+/=-]{8,}`)
	patternJWT         = regexp.MustCompile(`eyJ[a-zA-Z0-9_-]{8,}\.[a-zA-Z0-9_-]{8,}\.[a-zA-Z0-9_-]{8,}`)
	patternPrivateKey  = regexp.MustCompile(`(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----`)
	patternAWSKey      = regexp.MustCompile(`\bAKIA[0-9A-Z]{16}\b`)
)

// redact 把输出中的高置信凭据样式替换为固定占位符。
func redact(input string) string {
	out := patternKVSecret.ReplaceAllString(input, "$1$2[REDACTED]")
	out = patternBearer.ReplaceAllString(out, "$1 [REDACTED]")
	out = patternJWT.ReplaceAllString(out, "[REDACTED_JWT]")
	out = patternPrivateKey.ReplaceAllString(out, "[REDACTED_PRIVATE_KEY]")
	out = patternAWSKey.ReplaceAllString(out, "[REDACTED_AWS_KEY]")
	return out
}

// Executor 是 Linux 下的受限命令执行器。
type Executor struct {
	config Config
	limit  *rateLimiter
}

// NewExecutor 创建执行器；配置由调用方（config.Load）校验。
func NewExecutor(config Config) *Executor {
	if config.RatePerMinute <= 0 {
		config.RatePerMinute = 10
	}
	if config.MaxTimeout <= 0 {
		config.MaxTimeout = 120 * time.Second
	}
	if config.MaxOutputBytes <= 0 {
		config.MaxOutputBytes = 64 * 1024
	}
	return &Executor{config: config, limit: newRateLimiter(config.RatePerMinute)}
}

// Execute 渲染模板并执行命令，返回总是携带稳定原因枚举的结果。
//
// 安全约束：argv 直接 exec（无 shell）、强制超时（min(请求, 上限)）、
// stdout/stderr 各自按 MaxOutputBytes 截断、返回前统一脱敏。
func (e *Executor) Execute(parent context.Context, templateID string, params map[string]string,
	timeoutSeconds int) Result {
	if !e.config.Enabled {
		return failureResult(ErrReasonUnsupportedPlatform, "command executor is disabled on this agent")
	}
	if !e.limit.allow() {
		return failureResult(ErrReasonRateLimited, "agent command rate limit exceeded")
	}
	argv, err := Render(templateID, params)
	if err != nil {
		var unknown *ErrUnknownTemplate
		if errors.As(err, &unknown) {
			return failureResult(ErrReasonTemplateUnknown, err.Error())
		}
		var invalid *ErrInvalidParam
		if errors.As(err, &invalid) {
			return failureResult(ErrReasonParamInvalid, err.Error())
		}
		return failureResult(ErrReasonExecutionError, err.Error())
	}
	timeout := e.config.MaxTimeout
	if timeoutSeconds > 0 {
		requested := time.Duration(timeoutSeconds) * time.Second
		if requested < timeout {
			timeout = requested
		}
	}
	ctx, cancel := context.WithTimeout(parent, timeout)
	defer cancel()

	started := time.Now()
	cmd := exec.CommandContext(ctx, argv[0], argv[1:]...)
	var stdout, stderr cappedBuffer
	// 接线配置的输出上限：NewExecutor 已把 <=0 归一化为 64 KiB 默认值；
	// 此前未把该值传入 cappedBuffer，导致自定义 MaxOutputBytes 被静默忽略。
	stdout.max = e.config.MaxOutputBytes
	stderr.max = e.config.MaxOutputBytes
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	runErr := cmd.Run()
	duration := time.Since(started)

	result := Result{
		Success:    runErr == nil,
		ExitCode:   exitCodeOf(cmd, runErr),
		Stdout:     redact(stdout.String()),
		Stderr:     redact(stderr.String()),
		Truncated:  stdout.truncated || stderr.truncated,
		DurationMs: duration.Milliseconds(),
	}
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		result.Success = false
		result.Error = ErrReasonTimeout
		return result
	}
	if runErr != nil && result.ExitCode == 0 {
		// 进程未能启动（如可执行文件缺失）等非退出码失败。
		result.Error = ErrReasonExecutionError
	}
	return result
}

// cappedBuffer 捕获输出并按上限截断，truncated 标记是否触发上限。
type cappedBuffer struct {
	buf       bytes.Buffer
	max       int
	truncated bool
}

// Write 实现 io.Writer；超出 max 的部分丢弃并置 truncated。
func (c *cappedBuffer) Write(p []byte) (int, error) {
	if c.max <= 0 {
		c.max = 64 * 1024
	}
	remaining := c.max - c.buf.Len()
	if remaining <= 0 {
		c.truncated = true
		return len(p), nil
	}
	if len(p) > remaining {
		c.buf.Write(p[:remaining])
		c.truncated = true
		return len(p), nil
	}
	c.buf.Write(p)
	return len(p), nil
}

// String 返回缓冲内容（已按上限截断）。
func (c *cappedBuffer) String() string { return c.buf.String() }

// exitCodeOf 从运行错误中提取退出码；无法启动的进程返回 -1。
func exitCodeOf(cmd *exec.Cmd, runErr error) int {
	if runErr == nil {
		return 0
	}
	var exitErr *exec.ExitError
	if errors.As(runErr, &exitErr) {
		return exitErr.ExitCode()
	}
	return -1
}

// failureResult 构造未进入真实执行的失败结果。
func failureResult(reason, message string) Result {
	return Result{Success: false, ExitCode: -1, Error: reason, Stderr: message}
}
