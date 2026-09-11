package com.susumonitor.server.module.ai.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 一次运维问答的回答与调用审计摘要。 */
@Schema(description = "AI 运维问答回答")
public class AiQaVo {

    @Schema(description = "模型回答文本；降级时为确定性监控事实摘要", maxLength = 8000)
    private String answer;

    @Schema(description = "本次问答模型实际调用的只读工具列表")
    @JsonProperty("tool_calls")
    private List<AiToolCallVo> toolCalls = List.of();

    @Schema(description = "是否由模型生成（false 表示确定性降级）")
    @JsonProperty("model_used")
    private boolean modelUsed;

    @Schema(description = "是否发生降级（工具版失败回退无工具版或确定性摘要）")
    private boolean degraded;

    @Schema(description = "服务端固定 provider 标识", maxLength = 64)
    private String provider;

    @Schema(description = "服务端固定 model 标识", maxLength = 128)
    private String model;

    @Schema(description = "服务端固定 prompt 版本", maxLength = 64)
    @JsonProperty("prompt_version")
    private String promptVersion;

    @Schema(description = "本次调用用量")
    private AiUsageVo usage = new AiUsageVo();

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public List<AiToolCallVo> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<AiToolCallVo> toolCalls) { this.toolCalls = toolCalls; }
    public boolean isModelUsed() { return modelUsed; }
    public void setModelUsed(boolean modelUsed) { this.modelUsed = modelUsed; }
    public boolean isDegraded() { return degraded; }
    public void setDegraded(boolean degraded) { this.degraded = degraded; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
    public AiUsageVo getUsage() { return usage; }
    public void setUsage(AiUsageVo usage) { this.usage = usage; }
}
