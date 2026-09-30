package com.susumonitor.server.module.server.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 返回只读观察到的目标主机公钥信息，供管理员确认信任前核对。
 */
@Data
@Schema(description = "只读观察到的远端主机公钥，供管理员信任前核对")
public class SshHostKeyObservationVo {

    @JsonProperty("server_id")
    @Schema(description = "服务器 ID", minimum = "1")
    private Long serverId;
    @JsonProperty("host_key_algorithm")
    @Schema(description = "观察到的远端主机公钥算法")
    private String hostKeyAlgorithm;
    @JsonProperty("host_key_fingerprint")
    @Schema(description = "观察到的远端主机公钥 SHA-256 指纹")
    private String hostKeyFingerprint;
    @JsonProperty("registered_fingerprint")
    @Schema(description = "当前已登记的主机公钥指纹，未确认过为 null；与观察指纹不同表示密钥已变更")
    private String registeredFingerprint;
    @JsonProperty("observed_at")
    @Schema(description = "观察时间（UTC ISO-8601）")
    private OffsetDateTime observedAt;
}
