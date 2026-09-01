package com.susumonitor.server.module.ai.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/** 供应商返回的本次调用用量摘要。 */
@Schema(description = "模型用量")
public class AiUsageVo {
    @JsonProperty("input_tokens") private int inputTokens;
    @JsonProperty("output_tokens") private int outputTokens;
    @JsonProperty("total_tokens") private int totalTokens;
    @JsonProperty("estimated_cost") private BigDecimal estimatedCost = BigDecimal.ZERO;
    private String currency = "USD";
    public int getInputTokens() { return inputTokens; }
    public void setInputTokens(int value) { inputTokens = value; }
    public int getOutputTokens() { return outputTokens; }
    public void setOutputTokens(int value) { outputTokens = value; }
    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int value) { totalTokens = value; }
    public BigDecimal getEstimatedCost() { return estimatedCost; }
    public void setEstimatedCost(BigDecimal value) { estimatedCost = value; }
    public String getCurrency() { return currency; }
    public void setCurrency(String value) { currency = value; }
}
