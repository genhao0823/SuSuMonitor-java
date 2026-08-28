package com.susumonitor.server.module.server.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 返回已确认 SSH 主机公钥的非敏感信息和本次操作结果。
 */
// 自动生成响应字段访问方法，并由 Jackson 按全局 snake_case 规则序列化。
@Data
// 类级 @Schema 描述主机公钥确认结果模型，供 springdoc 生成响应模型说明。
@Schema(description = "SSH 主机公钥确认或轮换结果")
public class SshHostKeyVo {

    // 将 Java 属性映射为接口 JSON 字段 server_id。
    @JsonProperty("server_id")
    @Schema(description = "服务器 ID", minimum = "1")
    private Long serverId;
    // 将 Java 属性映射为接口 JSON 字段 host_key_algorithm。
    @JsonProperty("host_key_algorithm")
    @Schema(description = "主机公钥算法", example = "ssh-ed25519")
    private String hostKeyAlgorithm;
    // 将 Java 属性映射为接口 JSON 字段 host_key_fingerprint。
    @JsonProperty("host_key_fingerprint")
    @Schema(description = "主机公钥 SHA-256 指纹", example = "SHA256:REPLACEWITHBASE64FINGERPRINT")
    private String hostKeyFingerprint;
    // 描述操作结果字段，允许值即契约定义的三个操作结果。
    @Schema(description = "本次操作结果", allowableValues = {"confirmed", "rotated", "unchanged"})
    private String operation;
    // 将 Java 属性映射为接口 JSON 字段 verified_at。
    @JsonProperty("verified_at")
    @Schema(description = "确认或轮换时间（UTC ISO-8601）")
    private OffsetDateTime verifiedAt;
}
