package com.susumonitor.server.module.command.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 更新自动审批策略请求：开关 + 风险阈值（low/medium，high 拒绝）。 */
@Schema(description = "更新 AI 命令自动审批策略请求")
@JsonIgnoreProperties(ignoreUnknown = false)
public class AutoApprovalPolicyRequest {

    @NotNull
    @Schema(description = "是否启用自动审批", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean enabled;

    @NotBlank
    @JsonProperty("max_risk_level")
    @Schema(description = "自动审批风险阈值：low 仅低风险 / medium 中低风险（high 不可作为阈值）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String maxRiskLevel;

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean value) { enabled = value; }
    public String getMaxRiskLevel() { return maxRiskLevel; }
    public void setMaxRiskLevel(String value) { maxRiskLevel = value; }
}
