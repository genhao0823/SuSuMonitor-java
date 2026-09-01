package com.susumonitor.server.module.ai.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 对外返回一次只读 AI 诊断的结构化结果。 */
@Schema(description = "只读 AI 诊断结果")
public class AiDiagnosisVo {
    private String summary;
    private String severity;
    private List<AiFindingVo> findings;
    private List<AiEvidenceVo> evidence;
    private List<String> recommendations;
    private List<String> limitations;
    @JsonProperty("model_used")
    private boolean modelUsed;
    private String provider;
    private String model;
    @JsonProperty("prompt_version")
    private String promptVersion;
    private AiUsageVo usage;
    public String getSummary() { return summary; }
    public void setSummary(String value) { summary = value; }
    public String getSeverity() { return severity; }
    public void setSeverity(String value) { severity = value; }
    public List<AiFindingVo> getFindings() { return findings; }
    public void setFindings(List<AiFindingVo> value) { findings = value; }
    public List<AiEvidenceVo> getEvidence() { return evidence; }
    public void setEvidence(List<AiEvidenceVo> value) { evidence = value; }
    public List<String> getRecommendations() { return recommendations; }
    public void setRecommendations(List<String> value) { recommendations = value; }
    public List<String> getLimitations() { return limitations; }
    public void setLimitations(List<String> value) { limitations = value; }
    public boolean isModelUsed() { return modelUsed; }
    public void setModelUsed(boolean value) { modelUsed = value; }
    public String getProvider() { return provider; }
    public void setProvider(String value) { provider = value; }
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String value) { promptVersion = value; }
    public AiUsageVo getUsage() { return usage; }
    public void setUsage(AiUsageVo value) { usage = value; }
}
