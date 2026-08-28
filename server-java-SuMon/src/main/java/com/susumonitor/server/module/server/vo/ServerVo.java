package com.susumonitor.server.module.server.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 返回不包含任何 SSH 凭据明文或密文的服务器公开信息。
 */
// 自动生成当前 VO 的 getter、setter、toString、equals 和 hashCode 方法。
@Data
// 类级 @Schema 描述服务器公开模型，凭据字段刻意缺席，供 springdoc 生成响应模型。
@Schema(description = "服务器公开信息：不含任何 SSH 凭据明文或密文")
public class ServerVo {

    @Schema(description = "服务器 ID", minimum = "1")
    private Long id;
    @Schema(description = "服务器名称")
    private String name;
    @Schema(description = "服务器地址")
    private String host;
    @Schema(description = "服务器描述")
    private String description;
    // 描述状态字段，注明是数据库快照而非实时探测。
    @Schema(description = "数据库中持久化的最新状态快照，非实时探测")
    private String status;
    // 将 Java 的 sshHost 属性映射为接口 JSON 字段 ssh_host。
    @JsonProperty("ssh_host")
    @Schema(description = "SSH 连接地址")
    private String sshHost;
    // 将 Java 的 sshPort 属性映射为接口 JSON 字段 ssh_port。
    @JsonProperty("ssh_port")
    @Schema(description = "SSH 端口")
    private Integer sshPort;
    // 将 Java 的 sshUser 属性映射为接口 JSON 字段 ssh_user。
    @JsonProperty("ssh_user")
    @Schema(description = "SSH 登录用户名")
    private String sshUser;
    // 将 Java 的 sshAuthType 属性映射为接口 JSON 字段 ssh_auth_type。
    @JsonProperty("ssh_auth_type")
    @Schema(description = "SSH 认证方式", allowableValues = {"password", "private_key"})
    private String sshAuthType;
    // 将 Java 的 agentId 属性映射为接口 JSON 字段 agent_id。
    @JsonProperty("agent_id")
    @Schema(description = "Agent ID（未注册 Agent 为 null）")
    private String agentId;
    // 将 Java 的 agentStatus 属性映射为接口 JSON 字段 agent_status。
    @JsonProperty("agent_status")
    @Schema(description = "Agent 状态")
    private String agentStatus;
    // 将 Java 的 lastHeartbeatAt 属性映射为接口 JSON 字段 last_heartbeat_at。
    @JsonProperty("last_heartbeat_at")
    @Schema(description = "最近心跳时间（未收到心跳为 null）")
    private OffsetDateTime lastHeartbeatAt;
    // 将 Java 的 createdAt 属性映射为接口 JSON 字段 created_at。
    @JsonProperty("created_at")
    @Schema(description = "创建时间（UTC ISO-8601）")
    private OffsetDateTime createdAt;
    // 将 Java 的 updatedAt 属性映射为接口 JSON 字段 updated_at。
    @JsonProperty("updated_at")
    @Schema(description = "更新时间（UTC ISO-8601）")
    private OffsetDateTime updatedAt;

}
