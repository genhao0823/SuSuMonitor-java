package com.susumonitor.server.module.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 接收一次管理员运维问答请求；server_id 可空表示全局性问题。 */
@Schema(description = "AI 运维问答请求")
@JsonIgnoreProperties(ignoreUnknown = false)
public class AiQaRequest {

    @Min(1)
    @JsonProperty("server_id")
    @Schema(description = "目标服务器 ID；全局性问题可省略", minimum = "1")
    private Long serverId;

    @NotBlank
    @Size(max = 4000)
    @Schema(description = "管理员问题；按不可信文本处理", minLength = 1, maxLength = 4000,
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String question;

    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
}
