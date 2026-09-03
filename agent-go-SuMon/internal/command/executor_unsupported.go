//go:build !linux

package command

import (
	"context"
)

// Executor 是不支持平台上的空壳执行器，任何执行请求都返回 unsupported_platform。
type Executor struct {
	config Config
}

// NewExecutor 创建空壳执行器（与 Linux 版同签名，保证跨平台编译）。
func NewExecutor(config Config) *Executor {
	return &Executor{config: config}
}

// Execute 在非 Linux 平台一律返回 unsupported_platform，不执行任何命令。
func (e *Executor) Execute(parent context.Context, templateID string, params map[string]string,
	timeoutSeconds int) Result {
	_ = parent
	_ = templateID
	_ = params
	_ = timeoutSeconds
	_ = e.config
	return Result{Success: false, ExitCode: -1, Error: ErrReasonUnsupportedPlatform,
		Stderr: "command execution is not supported on this platform"}
}
