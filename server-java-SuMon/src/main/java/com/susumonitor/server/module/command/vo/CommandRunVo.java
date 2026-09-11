package com.susumonitor.server.module.command.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.Map;

/** 对外返回一次命令运行记录；result_json 已解析为结构化输出。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CommandRunVo {
    private Long id;
    @JsonProperty("execution_id")
    private String executionId;
    @JsonProperty("server_id")
    private Long serverId;
    @JsonProperty("template_id")
    private String templateId;
    private Map<String, String> params;
    @JsonProperty("rendered_command")
    private String renderedCommand;
    private String status;
    private String source;
    @JsonProperty("risk_level")
    private String riskLevel;
    @JsonProperty("approval_mode")
    private String approvalMode;
    private Proposal proposal;
    private Result result;
    @JsonProperty("exit_code")
    private Integer exitCode;
    @JsonProperty("proposer_id")
    private Long proposerId;
    @JsonProperty("approver_id")
    private Long approverId;
    @JsonProperty("expires_at")
    private OffsetDateTime expiresAt;
    @JsonProperty("created_at")
    private OffsetDateTime createdAt;
    @JsonProperty("completed_at")
    private OffsetDateTime completedAt;

    /** AI 建议元数据（manual 来源为 null）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Proposal {
        private String reason;
        private String model;
        @JsonProperty("prompt_version")
        private String promptVersion;
        public String getReason() { return reason; }
        public void setReason(String value) { reason = value; }
        public String getModel() { return model; }
        public void setModel(String value) { model = value; }
        public String getPromptVersion() { return promptVersion; }
        public void setPromptVersion(String value) { promptVersion = value; }
    }

    /** 结构化执行结果（未完成时为 null）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Result {
        private String stdout;
        private String stderr;
        private Boolean truncated;
        private String error;
        public String getStdout() { return stdout; }
        public void setStdout(String value) { stdout = value; }
        public String getStderr() { return stderr; }
        public void setStderr(String value) { stderr = value; }
        public Boolean getTruncated() { return truncated; }
        public void setTruncated(Boolean value) { truncated = value; }
        public String getError() { return error; }
        public void setError(String value) { error = value; }
    }

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getExecutionId() { return executionId; }
    public void setExecutionId(String value) { executionId = value; }
    public Long getServerId() { return serverId; }
    public void setServerId(Long value) { serverId = value; }
    public String getTemplateId() { return templateId; }
    public void setTemplateId(String value) { templateId = value; }
    public Map<String, String> getParams() { return params; }
    public void setParams(Map<String, String> value) { params = value; }
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
    public Proposal getProposal() { return proposal; }
    public void setProposal(Proposal value) { proposal = value; }
    public Result getResult() { return result; }
    public void setResult(Result value) { result = value; }
    public Integer getExitCode() { return exitCode; }
    public void setExitCode(Integer value) { exitCode = value; }
    public Long getProposerId() { return proposerId; }
    public void setProposerId(Long value) { proposerId = value; }
    public Long getApproverId() { return approverId; }
    public void setApproverId(Long value) { approverId = value; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime value) { expiresAt = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime value) { createdAt = value; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime value) { completedAt = value; }
}
