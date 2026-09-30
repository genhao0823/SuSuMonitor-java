package com.susumonitor.server.module.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 映射服务器持久化记录，供服务器管理模块读写 servers 表。
 */
@TableName("servers")
// 自动生成字段访问方法，并为敏感字段应用下方的对象方法排除规则。
@Data
public class ServerEntity {

    /** 服务器主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 服务器名称。 */
    private String name;

    /** 服务器业务地址。 */
    private String host;

    /** 服务器备注说明。 */
    private String description;

    /** 服务器运行状态。 */
    private String status;

    /** SSH 连接地址。 */
    @TableField("ssh_host")
    private String sshHost;

    /** SSH 连接端口。 */
    @TableField("ssh_port")
    private Integer sshPort;

    /** SSH 登录用户名。 */
    @TableField("ssh_user")
    private String sshUser;

    /** SSH 认证方式。 */
    @TableField("ssh_auth_type")
    private String sshAuthType;

    /** AES-GCM 加密后的 SSH 密码。 */
    @TableField("ssh_password_encrypted")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private String sshPasswordEncrypted;

    /** AES-GCM 加密后的 SSH 私钥。 */
    @TableField("ssh_private_key_encrypted")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private String sshPrivateKeyEncrypted;

    /** AES-GCM 加密后的 SSH 私钥口令。 */
    @TableField("ssh_private_key_passphrase_encrypted")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private String sshPrivateKeyPassphraseEncrypted;

    /** 已确认的 SSH 主机公钥算法。 */
    @TableField("ssh_host_key_algorithm")
    private String sshHostKeyAlgorithm;

    /** 已确认的 OpenSSH SHA-256 主机公钥指纹。 */
    @TableField("ssh_host_key_fingerprint")
    private String sshHostKeyFingerprint;

    /** 最近确认或轮换主机公钥的管理员用户 ID。 */
    @TableField("ssh_host_key_verified_by")
    private Long sshHostKeyVerifiedBy;

    /** 最近确认或轮换主机公钥的时间。 */
    @TableField("ssh_host_key_verified_at")
    private LocalDateTime sshHostKeyVerifiedAt;

    /** Agent 唯一标识。 */
    @TableField("agent_id")
    private String agentId;

    /** Agent Token 的不可逆哈希。 */
    @TableField("agent_token_hash")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private String agentTokenHash;

    /** 轮换宽限期内旧 Token SHA-256 摘要：轮换瞬间在线 Agent 可用旧 Token 完成重连。 */
    private String agentTokenHashPrev;

    /** 旧 Token 宽限期截止时间（UTC）；为空表示无宽限或已轮换清理。 */
    private LocalDateTime agentTokenGraceUntil;

    /** Agent Token 首次创建时间。 */
    @TableField("agent_token_created_at")
    private LocalDateTime agentTokenCreatedAt;

    /** Agent Token 最近一次轮换时间。 */
    @TableField("agent_token_rotated_at")
    private LocalDateTime agentTokenRotatedAt;

    /** Agent Token 撤销时间。 */
    @TableField("agent_token_revoked_at")
    private LocalDateTime agentTokenRevokedAt;

    /** Agent 在线状态。 */
    @TableField("agent_status")
    private String agentStatus;

    /** 最近一次 Agent 心跳时间。 */
    @TableField("last_heartbeat_at")
    private LocalDateTime lastHeartbeatAt;

    /** Agent 投递积压条数，来自心跳携带的遥测。 */
    @TableField("delivery_pending_count")
    private Long deliveryPendingCount;

    /** Agent 投递积压字节数，来自心跳携带的遥测。 */
    @TableField("delivery_pending_bytes")
    private Long deliveryPendingBytes;

    /** Agent 积压最旧采样时间，来自心跳携带的遥测。 */
    @TableField("delivery_oldest_collected_at")
    private LocalDateTime deliveryOldestCollectedAt;

    /** Agent 因缓冲满丢弃的采样计数，来自心跳携带的遥测。 */
    @TableField("delivery_drop_count")
    private Long deliveryDropCount;

    /** Agent 本地死信条数，来自心跳携带的遥测。 */
    @TableField("delivery_dead_letter_count")
    private Long deliveryDeadLetterCount;

    /** Agent 本地死信字节数，来自心跳携带的遥测。 */
    @TableField("delivery_dead_letter_bytes")
    private Long deliveryDeadLetterBytes;

    /** 软删除标记，0 表示有效，1 表示已删除。 */
    private Boolean deleted;

    /** 软删除发生时间。 */
    @TableField("deleted_at")
    private LocalDateTime deletedAt;

    /** 软删除唯一标识，用于释放有效记录的 host 唯一约束。 */
    @TableField("delete_token")
    private String deleteToken;

    /** 记录创建时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 记录最后更新时间。 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
