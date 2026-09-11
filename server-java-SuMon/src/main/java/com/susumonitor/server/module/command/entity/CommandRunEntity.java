package com.susumonitor.server.module.command.entity;

import java.time.LocalDateTime;

/** 映射 AI 命令域 M1 审批制审计记录（V29 ai_command_runs）。 */
public class CommandRunEntity {
    private Long id;
    private String executionId;
    private String requestId;
    private Long proposerId;
    private Long approverId;
    private Long serverId;
    private String templateId;
    private String paramsJson;
    private String paramsHash;
    private String renderedCommand;
    private String status;
    private String source;
    private String riskLevel;
    private String approvalMode;
    private String proposalJson;
    private String resultJson;
    private Integer exitCode;
    private Boolean truncated;
    private Long durationMs;
    private Integer errorCode;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getExecutionId() { return executionId; }
    public void setExecutionId(String value) { executionId = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public Long getProposerId() { return proposerId; }
    public void setProposerId(Long value) { proposerId = value; }
    public Long getApproverId() { return approverId; }
    public void setApproverId(Long value) { approverId = value; }
    public Long getServerId() { return serverId; }
    public void setServerId(Long value) { serverId = value; }
    public String getTemplateId() { return templateId; }
    public void setTemplateId(String value) { templateId = value; }
    public String getParamsJson() { return paramsJson; }
    public void setParamsJson(String value) { paramsJson = value; }
    public String getParamsHash() { return paramsHash; }
    public void setParamsHash(String value) { paramsHash = value; }
    public String getRenderedCommand() { return renderedCommand; }
    public void setRenderedCommand(String value) { renderedCommand = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getSource() { return source; }
    public void setSource(String value) { source = value; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String value) { riskLevel = value; }
    public String getApprovalMode() { return approvalMode; }
    public void setApprovalMode(String value) { approvalMode = value; }
    public String getProposalJson() { return proposalJson; }
    public void setProposalJson(String value) { proposalJson = value; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String value) { resultJson = value; }
    public Integer getExitCode() { return exitCode; }
    public void setExitCode(Integer value) { exitCode = value; }
    public Boolean getTruncated() { return truncated; }
    public void setTruncated(Boolean value) { truncated = value; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long value) { durationMs = value; }
    public Integer getErrorCode() { return errorCode; }
    public void setErrorCode(Integer value) { errorCode = value; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime value) { expiresAt = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime value) { completedAt = value; }
}
