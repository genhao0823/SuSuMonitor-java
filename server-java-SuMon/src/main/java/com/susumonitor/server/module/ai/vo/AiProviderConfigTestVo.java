package com.susumonitor.server.module.ai.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/** 个人 AI 服务商连通性测试结果；ok=false 时给出稳定错误码与可读原因。 */
@Schema(description = "AI 个人服务商连通性测试结果")
public class AiProviderConfigTestVo {

    @Schema(description = "测试是否成功")
    private Boolean ok;

    @JsonProperty("latency_ms")
    @Schema(description = "测试调用耗时（毫秒，含一次最小 chat 请求）")
    private Long latencyMs;

    @JsonProperty("error_code")
    @Schema(description = "失败时的稳定业务错误码；成功为 null")
    private Integer errorCode;

    @Schema(description = "失败时的可读原因；成功为 null")
    private String message;

    public Boolean getOk() { return ok; }
    public void setOk(Boolean value) { ok = value; }
    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long value) { latencyMs = value; }
    public Integer getErrorCode() { return errorCode; }
    public void setErrorCode(Integer value) { errorCode = value; }
    public String getMessage() { return message; }
    public void setMessage(String value) { message = value; }
}
