package com.susumonitor.server.module.server.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 返回一条 SSH 连接测试历史记录，成功与失败均含错误码区分。
 */
@Data
@Schema(description = "一条 SSH 连接测试历史记录（成功与失败均返回）")
public class SshTestHistoryVo {

    @JsonProperty("server_id")
    @Schema(description = "测试的服务器 ID")
    private Long serverId;
    @Schema(description = "连接测试是否成功")
    private boolean connected;
    // 失败时的业务错误码，成功为 NULL。
    @JsonProperty("error_code")
    @Schema(description = "失败时的业务错误码（50002/50003/50400 等），成功为 null")
    private Integer errorCode;
    @JsonProperty("host_key_algorithm")
    @Schema(description = "成功时的远端主机公钥算法")
    private String hostKeyAlgorithm;
    @JsonProperty("host_key_fingerprint")
    @Schema(description = "成功时的远端主机公钥指纹")
    private String hostKeyFingerprint;
    @JsonProperty("auth_type")
    @Schema(description = "认证方式", allowableValues = {"password", "private_key"})
    private String authType;
    @JsonProperty("duration_ms")
    @Schema(description = "测试耗时毫秒")
    private long durationMs;
    @JsonProperty("tested_at")
    @Schema(description = "测试发生时间（UTC ISO-8601）")
    private OffsetDateTime testedAt;
}
