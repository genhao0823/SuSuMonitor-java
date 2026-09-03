package com.susumonitor.server.module.command.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** AI 命令建议请求：目标服务器 + 自然语言运维意图。 */
@Schema(description = "AI 命令建议请求")
@JsonIgnoreProperties(ignoreUnknown = false)
public class CommandSuggestionRequest {

    @NotNull
    @Min(1)
    @JsonProperty("server_id")
    @Schema(description = "目标服务器 ID", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long serverId;

    @NotBlank
    @Size(max = 2000)
    @Schema(description = "运维意图（自然语言）；按不可信文本处理", requiredMode = Schema.RequiredMode.REQUIRED)
    private String intent;

    public Long getServerId() { return serverId; }
    public void setServerId(Long value) { serverId = value; }
    public String getIntent() { return intent; }
    public void setIntent(String value) { intent = value; }
}
