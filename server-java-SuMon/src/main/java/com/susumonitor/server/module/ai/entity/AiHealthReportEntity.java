package com.susumonitor.server.module.ai.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 映射 AI 定时健康报告存储行：每个自然日至多一份报告（report_date 唯一键），不保存原始 prompt 或凭据。 */
public class AiHealthReportEntity {
    private Long id;
    private LocalDate reportDate;
    private String status;
    private String provider;
    private String model;
    private String promptVersion;
    private String summary;
    private String resultJson;
    private Integer errorCode;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
    private Long durationMs;
    private LocalDateTime createdAt;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public LocalDate getReportDate() { return reportDate; }
    public void setReportDate(LocalDate value) { reportDate = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getProvider() { return provider; }
    public void setProvider(String value) { provider = value; }
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String value) { promptVersion = value; }
    public String getSummary() { return summary; }
    public void setSummary(String value) { summary = value; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String value) { resultJson = value; }
    public Integer getErrorCode() { return errorCode; }
    public void setErrorCode(Integer value) { errorCode = value; }
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
