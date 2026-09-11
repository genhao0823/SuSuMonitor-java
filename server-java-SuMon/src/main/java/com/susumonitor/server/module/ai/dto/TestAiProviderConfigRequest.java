package com.susumonitor.server.module.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 测试个人 AI 服务商连通性的请求体；api_key 空白时复用已保存的 Key。 */
@Schema(description = "AI 个人服务商连通性测试请求")
@JsonIgnoreProperties(ignoreUnknown = false)
public class TestAiProviderConfigRequest {

    @NotBlank
    @Size(max = 512)
    @JsonProperty("base_url")
    @Schema(description = "待测试的 OpenAI 兼容 endpoint", minLength = 1, maxLength = 512,
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String baseUrl;

    @Size(max = 256)
    @JsonProperty("api_key")
    @Schema(description = "待测试的明文 API Key；空白时复用已存 Key", maxLength = 256)
    private String apiKey;

    @NotBlank
    @Size(max = 128)
    @Schema(description = "待测试的模型名称", minLength = 1, maxLength = 128,
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String model;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl = value; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String value) { apiKey = value; }
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
}
