package com.susumonitor.server.module.ai.entity;

import java.time.LocalDateTime;

/** 映射 AI 告警智能解释存储行：每条告警记录至多一条成功解释，不保存原始 prompt 或凭据。 */
public class AiAlertExplanationEntity {
    private Long id;
    private Long alertRecordId;
    private Long serverId;
    private Long ruleId;
    private String eventId;
    private String promptVersion;
    private String provider;
    private String model;
    private String summary;
    private String resultJson;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
    private Long durationMs;
    private LocalDateTime createdAt;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getAlertRecordId() { return alertRecordId; }
    public void setAlertRecordId(Long value) { alertRecordId = value; }
    public Long getServerId() { return serverId; }
    public void setServerId(Long value) { serverId = value; }
    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long value) { ruleId = value; }
    public String getEventId() { return eventId; }
    public void setEventId(String value) { eventId = value; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String value) { promptVersion = value; }
    public String getProvider() { return provider; }
    public void setProvider(String value) { provider = value; }
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
    public String getSummary() { return summary; }
    public void setSummary(String value) { summary = value; }
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
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
}
