package com.susumonitor.server.module.ai.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/** 模型返回的一条告警智能解释（结构化、只读分析，不含可执行指令）。 */
@Schema(description = "AI 告警智能解释")
public class AiAlertExplanationVo {
    @JsonProperty("record_id")
    private Long recordId;
    private String summary;
    @JsonProperty("possible_causes")
    private List<String> possibleCauses;
    private List<String> impact;
    private List<String> suggestions;
    private List<String> limitations;
    private AiUsageVo usage;
    private String provider;
    private String model;
    @JsonProperty("prompt_version")
    private String promptVersion;
    @JsonProperty("created_at")
    private OffsetDateTime createdAt;
    public Long getRecordId() { return recordId; }
    public void setRecordId(Long value) { recordId = value; }
    public String getSummary() { return summary; }
    public void setSummary(String value) { summary = value; }
    public List<String> getPossibleCauses() { return possibleCauses; }
    public void setPossibleCauses(List<String> value) { possibleCauses = value; }
    public List<String> getImpact() { return impact; }
    public void setImpact(List<String> value) { impact = value; }
    public List<String> getSuggestions() { return suggestions; }
    public void setSuggestions(List<String> value) { suggestions = value; }
    public List<String> getLimitations() { return limitations; }
    public void setLimitations(List<String> value) { limitations = value; }
    public AiUsageVo getUsage() { return usage; }
    public void setUsage(AiUsageVo value) { usage = value; }
    public String getProvider() { return provider; }
    public void setProvider(String value) { provider = value; }
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String value) { promptVersion = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime value) { createdAt = value; }
}
