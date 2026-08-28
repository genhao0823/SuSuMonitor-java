package com.susumonitor.server.module.server.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 返回严格校验主机身份并完成凭据认证后的 SSH 连接测试结果。
 */
// 自动生成当前 VO 的 getter、setter、toString、equals 和 hashCode 方法。
@Data
// 类级 @Schema 描述 SSH 连接测试结果模型，供 springdoc 生成响应模型说明。
@Schema(description = "SSH 连接测试结果")
public class SshTestVo {

    // 将 Java 属性映射为接口 JSON 字段 server_id。
    @JsonProperty("server_id")
    @Schema(description = "服务器 ID", minimum = "1")
    private Long serverId;
    @Schema(description = "连接测试是否成功（成功响应中恒为 true）")
    private boolean connected;
    // 将 Java 属性映射为接口 JSON 字段 host_key_algorithm。
    @JsonProperty("host_key_algorithm")
    @Schema(description = "远端主机公钥算法", example = "ssh-ed25519")
    private String hostKeyAlgorithm;
    // 将 Java 属性映射为接口 JSON 字段 host_key_fingerprint。
    @JsonProperty("host_key_fingerprint")
    @Schema(description = "远端主机公钥 SHA-256 指纹", example = "SHA256:REPLACEWITHBASE64FINGERPRINT")
    private String hostKeyFingerprint;
    // 将 Java 属性映射为接口 JSON 字段 auth_type。
    @JsonProperty("auth_type")
    @Schema(description = "实际使用的认证方式", allowableValues = {"password", "private_key"})
    private String authType;
    // 将 Java 属性映射为接口 JSON 字段 duration_ms。
    @JsonProperty("duration_ms")
    @Schema(description = "测试耗时毫秒", minimum = "0")
    private long durationMs;
    // 将 Java 属性映射为接口 JSON 字段 tested_at。
    @JsonProperty("tested_at")
    @Schema(description = "测试发生时间（UTC ISO-8601）")
    private OffsetDateTime testedAt;

}
