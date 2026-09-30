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
@Schema(description = "SSH 主机公钥确认或轮换结果")
public class SshHostKeyVo {

    @JsonProperty("server_id")
    @Schema(description = "服务器 ID", minimum = "1")
    private Long serverId;
    @JsonProperty("host_key_algorithm")
    @Schema(description = "主机公钥算法", example = "ssh-ed25519")
    private String hostKeyAlgorithm;
    @JsonProperty("host_key_fingerprint")
    @Schema(description = "主机公钥 SHA-256 指纹", example = "SHA256:REPLACEWITHBASE64FINGERPRINT")
    private String hostKeyFingerprint;
    @Schema(description = "本次操作结果", allowableValues = {"confirmed", "rotated", "unchanged"})
    private String operation;
    @JsonProperty("verified_at")
    @Schema(description = "确认或轮换时间（UTC ISO-8601）")
    private OffsetDateTime verifiedAt;
}
