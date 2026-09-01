package com.susumonitor.server.module.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 接收一次管理员发起的只读服务器诊断请求。 */
@Schema(description = "只读 AI 诊断请求")
@JsonIgnoreProperties(ignoreUnknown = false)
public class AiDiagnosisRequest {

    @NotNull
    @Min(1)
    @JsonProperty("server_id")
    @Schema(description = "目标服务器 ID", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long serverId;

    @NotBlank
    @Size(max = 4000)
    @Schema(description = "管理员问题；按不可信文本处理", minLength = 1, maxLength = 4000,
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String question;

    @NotNull
    @Min(0)
    @Max(1440)
    @JsonProperty("history_minutes")
    @Schema(description = "读取的历史指标窗口（分钟）", minimum = "0", maximum = "1440",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer historyMinutes;

    public Long getServerId() { return serverId; }
    public void setServerId(Long serverId) { this.serverId = serverId; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public Integer getHistoryMinutes() { return historyMinutes; }
    public void setHistoryMinutes(Integer historyMinutes) { this.historyMinutes = historyMinutes; }
}
