package com.susumonitor.server.module.server.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 返回只读观察到的目标主机公钥信息，供管理员确认信任前核对。
 */
@Data
public class SshHostKeyObservationVo {

    // 将 Java 属性映射为接口 JSON 字段 server_id。
    @JsonProperty("server_id")
    private Long serverId;
    // 将 Java 属性映射为接口 JSON 字段 host_key_algorithm。
    @JsonProperty("host_key_algorithm")
    private String hostKeyAlgorithm;
    // 将 Java 属性映射为接口 JSON 字段 host_key_fingerprint。
    @JsonProperty("host_key_fingerprint")
    private String hostKeyFingerprint;
    // 将 Java 属性映射为接口 JSON 字段 registered_fingerprint（当前已登记指纹，未确认过为 null）。
    @JsonProperty("registered_fingerprint")
    private String registeredFingerprint;
    // 将 Java 属性映射为接口 JSON 字段 observed_at。
    @JsonProperty("observed_at")
    private OffsetDateTime observedAt;
}
