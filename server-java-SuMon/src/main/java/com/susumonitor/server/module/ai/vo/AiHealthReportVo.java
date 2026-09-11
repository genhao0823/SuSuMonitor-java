package com.susumonitor.server.module.ai.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 一份定时健康报告的回看视图：摘要/关注点 + 聚合事实快照，degraded 时 summary 为空并带 error_code。 */
@Schema(description = "AI 定时健康报告")
public class AiHealthReportVo {
    private Long id;
    @JsonProperty("report_date")
    private LocalDate reportDate;
    private String status;
    private String provider;
    private String model;
    @JsonProperty("prompt_version")
    private String promptVersion;
    private String summary;
    @JsonProperty("top_concerns")
    private List<String> topConcerns;
    private List<String> limitations;
    private Map<String, Object> facts;
    @JsonProperty("error_code")
    private Integer errorCode;
    private AiUsageVo usage;
    @JsonProperty("duration_ms")
    private Long durationMs;
    @JsonProperty("created_at")
    private OffsetDateTime createdAt;
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
    public List<String> getTopConcerns() { return topConcerns; }
    public void setTopConcerns(List<String> value) { topConcerns = value; }
    public List<String> getLimitations() { return limitations; }
    public void setLimitations(List<String> value) { limitations = value; }
    public Map<String, Object> getFacts() { return facts; }
    public void setFacts(Map<String, Object> value) { facts = value; }
    public Integer getErrorCode() { return errorCode; }
    public void setErrorCode(Integer value) { errorCode = value; }
    public AiUsageVo getUsage() { return usage; }
    public void setUsage(AiUsageVo value) { usage = value; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long value) { durationMs = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime value) { createdAt = value; }
}
