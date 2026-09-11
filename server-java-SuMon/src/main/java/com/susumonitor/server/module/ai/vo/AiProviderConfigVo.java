package com.susumonitor.server.module.ai.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/** 管理员个人 AI 服务商配置视图；api_key 仅返回掩码，永不含明文。 */
@Schema(description = "AI 个人服务商配置视图")
public class AiProviderConfigVo {

    @Schema(description = "是否已配置个人 AI 服务商")
    private Boolean configured;

    @Schema(description = "服务商类型；当前固定 openai-compatible")
    private String provider;

    @JsonProperty("base_url")
    @Schema(description = "OpenAI 兼容 endpoint")
    private String baseUrl;

    @Schema(description = "模型名称")
    private String model;

    @JsonProperty("api_key_masked")
    @Schema(description = "API Key 掩码（保留前 3 后 4）；未配置 Key 时为 ****")
    private String apiKeyMasked;

    @Schema(description = "是否启用；关闭后回退全局配置")
    private Boolean enabled;

    @JsonProperty("updated_at")
    @Schema(description = "最近更新时间（服务器本地时间 ISO-8601）")
    private String updatedAt;

    public Boolean getConfigured() { return configured; }
    public void setConfigured(Boolean value) { configured = value; }
    public String getProvider() { return provider; }
    public void setProvider(String value) { provider = value; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl = value; }
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
    public String getApiKeyMasked() { return apiKeyMasked; }
    public void setApiKeyMasked(String value) { apiKeyMasked = value; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean value) { enabled = value; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String value) { updatedAt = value; }
}
