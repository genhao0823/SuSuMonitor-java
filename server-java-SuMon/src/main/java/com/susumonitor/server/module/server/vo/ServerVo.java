package com.susumonitor.server.module.server.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 返回不包含任何 SSH 凭据明文或密文的服务器公开信息。
 */
@Data
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
    @Schema(description = "数据库中持久化的最新状态快照，非实时探测")
    private String status;
    @JsonProperty("ssh_host")
    @Schema(description = "SSH 连接地址")
    private String sshHost;
    @JsonProperty("ssh_port")
    @Schema(description = "SSH 端口")
    private Integer sshPort;
    @JsonProperty("ssh_user")
    @Schema(description = "SSH 登录用户名")
    private String sshUser;
    @JsonProperty("ssh_auth_type")
    @Schema(description = "SSH 认证方式", allowableValues = {"password", "private_key"})
    private String sshAuthType;
    @JsonProperty("agent_id")
    @Schema(description = "Agent ID（未注册 Agent 为 null）")
    private String agentId;
    @JsonProperty("agent_status")
    @Schema(description = "Agent 状态")
    private String agentStatus;
    @JsonProperty("last_heartbeat_at")
    @Schema(description = "最近心跳时间（未收到心跳为 null）")
    private OffsetDateTime lastHeartbeatAt;
    @JsonProperty("created_at")
    @Schema(description = "创建时间（UTC ISO-8601）")
    private OffsetDateTime createdAt;
    @JsonProperty("updated_at")
    @Schema(description = "更新时间（UTC ISO-8601）")
    private OffsetDateTime updatedAt;

}
