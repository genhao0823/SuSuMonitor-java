package main

import (
	"context"
	"encoding/json"
	"log/slog"
	"sync"
	"time"

	"agent-go-SuMon/internal/command"
	"agent-go-SuMon/internal/config"
	"agent-go-SuMon/internal/wsclient"
)

// commandAgent 处理 command.* 协议消息：校验归属与白名单，执行并原路回传结果。
//
// 安全模型与 terminal 通道一致：payload.server_id 必须等于本 Agent 已认证的
// server_id；执行只接受内建白名单模板；结果以执行请求的 message_id 关联回传。
type commandAgent struct {
	client     *wsclient.Client
	executor   *command.Executor
	logger     *slog.Logger
	serverID   int64
	enabled    bool
	requestIDs sync.Map
}

// newCommandAgent 根据配置构造命令协议适配器。
func newCommandAgent(cfg *config.Config, client *wsclient.Client, logger *slog.Logger) *commandAgent {
	return &commandAgent{
		client: client,
		executor: command.NewExecutor(command.Config{
			Enabled:        cfg.CommandEnabled,
			MaxTimeout:     time.Duration(cfg.CommandMaxTimeoutSeconds) * time.Second,
			MaxOutputBytes: cfg.CommandMaxOutputBytes,
			RatePerMinute:  cfg.CommandRatePerMinute,
		}),
		logger:  logger,
		serverID: cfg.ServerID,
		enabled:  cfg.CommandEnabled,
	}
}

// handle 分发 command.* 下行消息；当前仅实现 command.execute。
func (a *commandAgent) handle(ctx context.Context, message wsclient.AgentMessage) {
	if message.Type != "command.execute" {
		a.logger.Debug("unsupported command message", "type", message.Type)
		return
	}
	a.handleExecute(ctx, message)
}

// handleExecute 校验载荷、执行模板命令并回传 command.result。
//
// 任何失败都以稳定原因枚举回 result（而非静默丢弃），保证服务端审计行可闭环。
func (a *commandAgent) handleExecute(ctx context.Context, message wsclient.AgentMessage) {
	var payload wsclient.CommandExecutePayload
	if err := json.Unmarshal(message.Payload, &payload); err != nil ||
		payload.ExecutionID == "" || payload.Template == "" || payload.ServerID != a.serverID {
		a.logger.Warn("command.execute rejected: invalid payload or server mismatch",
			"server_id", payload.ServerID, "execution_id", payload.ExecutionID)
		a.sendResult(ctx, message.MessageID, payload, command.Result{
			Success: false, ExitCode: -1, Error: command.ErrReasonParamInvalid,
			Stderr: "invalid payload or server mismatch",
		})
		return
	}
	a.requestIDs.Store(payload.ExecutionID, message.MessageID)
	a.logger.Info("command.execute received", "execution_id", payload.ExecutionID,
		"template", payload.Template, "timeout_seconds", payload.TimeoutSeconds,
		"enabled", a.enabled)

	result := a.executor.Execute(ctx, payload.Template, payload.Params, payload.TimeoutSeconds)
	a.logger.Info("command.execute finished", "execution_id", payload.ExecutionID,
		"success", result.Success, "exit_code", result.ExitCode, "error", result.Error,
		"truncated", result.Truncated, "duration_ms", result.DurationMs)
	a.requestIDs.Delete(payload.ExecutionID)
	a.sendResult(ctx, message.MessageID, payload, result)
}

// sendResult 以请求的 message_id 关联回传 command.result，发送失败仅记日志。
func (a *commandAgent) sendResult(ctx context.Context, requestMessageID string,
	payload wsclient.CommandExecutePayload, result command.Result) {
	resultPayload := wsclient.CommandResultPayload{
		ServerID:    a.serverID,
		ExecutionID: payload.ExecutionID,
		Success:     result.Success,
		ExitCode:    result.ExitCode,
		Stdout:      result.Stdout,
		Stderr:      result.Stderr,
		Truncated:   result.Truncated,
		DurationMs:  result.DurationMs,
		Error:       result.Error,
	}
	sendCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := a.client.SendMessage(sendCtx, wsclient.NewMessageWithID("command.result",
		requestMessageID, resultPayload)); err != nil {
		a.logger.Warn("command.result send failed", "execution_id", payload.ExecutionID)
	}
}
