package com.susumonitor.server.module.ai.entity;

import java.time.LocalDateTime;

/** 映射 AI 运维问答最小审计记录，不保存原始问题、prompt 或供应商原始响应。 */
public class AiQaRunEntity {
    private Long id;
    private String requestId;
    private String correlationId;
    private Long actorId;
    private Long serverId;
    private String promptVersion;
    private String provider;
    private String model;
    private String status;
    private String questionHash;
    private String toolCallsJson;
    private String resultJson;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
    private Long durationMs;
    private Integer errorCode;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String value) { correlationId = value; }
    public Long getActorId() { return actorId; }
    public void setActorId(Long value) { actorId = value; }
    public Long getServerId() { return serverId; }
    public void setServerId(Long value) { serverId = value; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String value) { promptVersion = value; }
    public String getProvider() { return provider; }
    public void setProvider(String value) { provider = value; }
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getQuestionHash() { return questionHash; }
    public void setQuestionHash(String value) { questionHash = value; }
    public String getToolCallsJson() { return toolCallsJson; }
    public void setToolCallsJson(String value) { toolCallsJson = value; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String value) { resultJson = value; }
    public Integer getInputTokens() { return inputTokens; }
    public void setInputTokens(Integer value) { inputTokens = value; }
    public Integer getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Integer value) { outputTokens = value; }
    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer value) { totalTokens = value; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long value) { durationMs = value; }
    public Integer getErrorCode() { return errorCode; }
    public void setErrorCode(Integer value) { errorCode = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime value) { completedAt = value; }
}
