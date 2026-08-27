package com.susumonitor.server.module.server.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 返回一条 SSH 连接测试历史记录，成功与失败均含错误码区分。
 */
@Data
public class SshTestHistoryVo {

    // 将 Java 属性映射为接口 JSON 字段 server_id。
    @JsonProperty("server_id")
    private Long serverId;
    // 连接测试是否成功。
    private boolean connected;
    // 失败时的业务错误码，成功为 NULL。
    @JsonProperty("error_code")
    private Integer errorCode;
    // 将 Java 属性映射为接口 JSON 字段 host_key_algorithm。
    @JsonProperty("host_key_algorithm")
    private String hostKeyAlgorithm;
    // 将 Java 属性映射为接口 JSON 字段 host_key_fingerprint。
    @JsonProperty("host_key_fingerprint")
    private String hostKeyFingerprint;
    // 将 Java 属性映射为接口 JSON 字段 auth_type。
    @JsonProperty("auth_type")
    private String authType;
    // 将 Java 属性映射为接口 JSON 字段 duration_ms。
    @JsonProperty("duration_ms")
    private long durationMs;
    // 将 Java 属性映射为接口 JSON 字段 tested_at。
    @JsonProperty("tested_at")
    private OffsetDateTime testedAt;
}
