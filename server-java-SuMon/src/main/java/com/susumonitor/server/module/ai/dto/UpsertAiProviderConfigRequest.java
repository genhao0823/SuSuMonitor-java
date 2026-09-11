package com.susumonitor.server.module.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 保存（或覆盖）管理员个人 AI 服务商配置的请求体。 */
@Schema(description = "AI 个人服务商配置保存请求")
@JsonIgnoreProperties(ignoreUnknown = false)
public class UpsertAiProviderConfigRequest {

    @NotBlank
    @Size(max = 512)
    @JsonProperty("base_url")
    @Schema(description = "OpenAI 兼容 endpoint（默认强制 HTTPS；含 /v1 前缀）", minLength = 1,
            maxLength = 512, requiredMode = Schema.RequiredMode.REQUIRED)
    private String baseUrl;

    @Size(max = 256)
    @JsonProperty("api_key")
    @Schema(description = "明文 API Key；省略或空白表示保留已存 Key", maxLength = 256)
    private String apiKey;

    @NotBlank
    @Size(max = 128)
    @Schema(description = "模型名称", minLength = 1, maxLength = 128,
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String model;

    @Schema(description = "是否启用；关闭后该用户回退全局配置", defaultValue = "true")
    private Boolean enabled = Boolean.TRUE;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl = value; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String value) { apiKey = value; }
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean value) { enabled = value; }
}
