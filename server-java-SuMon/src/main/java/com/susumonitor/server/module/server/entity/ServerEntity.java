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
// 将当前类映射为 MyBatis-Plus 对应的 servers 数据库表。
@TableName("servers")
// 自动生成字段访问方法，并为敏感字段应用下方的对象方法排除规则。
@Data
public class ServerEntity {

    /** 服务器主键。 */
    // 将 id 映射为数据库自增主键，并在插入后回写生成的 ID。
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
    // 将 Java 属性映射到 servers.ssh_host 字段。
    @TableField("ssh_host")
    private String sshHost;

    /** SSH 连接端口。 */
    // 将 Java 属性映射到 servers.ssh_port 字段。
    @TableField("ssh_port")
    private Integer sshPort;

    /** SSH 登录用户名。 */
    // 将 Java 属性映射到 servers.ssh_user 字段。
    @TableField("ssh_user")
    private String sshUser;

    /** SSH 认证方式。 */
    // 将 Java 属性映射到 servers.ssh_auth_type 字段。
    @TableField("ssh_auth_type")
    private String sshAuthType;

    /** AES-GCM 加密后的 SSH 密码。 */
    // 将 Java 属性映射到 SSH 密码密文字段。
    @TableField("ssh_password_encrypted")
    // 防止 Lombok 生成的 toString 方法输出 SSH 密码密文。
    @ToString.Exclude
    // 防止 Lombok 生成的 equals 和 hashCode 使用 SSH 密码密文。
    @EqualsAndHashCode.Exclude
    private String sshPasswordEncrypted;

    /** AES-GCM 加密后的 SSH 私钥。 */
    // 将 Java 属性映射到 SSH 私钥密文字段。
    @TableField("ssh_private_key_encrypted")
    // 防止 Lombok 生成的 toString 方法输出 SSH 私钥密文。
    @ToString.Exclude
    // 防止 Lombok 生成的 equals 和 hashCode 使用 SSH 私钥密文。
    @EqualsAndHashCode.Exclude
    private String sshPrivateKeyEncrypted;

    /** AES-GCM 加密后的 SSH 私钥口令。 */
    // 将 Java 属性映射到 SSH 私钥口令密文字段。
    @TableField("ssh_private_key_passphrase_encrypted")
    // 防止 Lombok 生成的 toString 方法输出 SSH 私钥口令密文。
    @ToString.Exclude
    // 防止 Lombok 生成的 equals 和 hashCode 使用 SSH 私钥口令密文。
    @EqualsAndHashCode.Exclude
    private String sshPrivateKeyPassphraseEncrypted;

    /** 已确认的 SSH 主机公钥算法。 */
    // 将 Java 属性映射到 SSH 主机公钥算法字段。
    @TableField("ssh_host_key_algorithm")
    private String sshHostKeyAlgorithm;

    /** 已确认的 OpenSSH SHA-256 主机公钥指纹。 */
    // 将 Java 属性映射到 SSH 主机公钥指纹字段。
    @TableField("ssh_host_key_fingerprint")
    private String sshHostKeyFingerprint;

    /** 最近确认或轮换主机公钥的管理员用户 ID。 */
    // 将 Java 属性映射到 SSH 主机公钥确认人字段。
    @TableField("ssh_host_key_verified_by")
    private Long sshHostKeyVerifiedBy;

    /** 最近确认或轮换主机公钥的时间。 */
    // 将 Java 属性映射到 SSH 主机公钥确认时间字段。
    @TableField("ssh_host_key_verified_at")
    private LocalDateTime sshHostKeyVerifiedAt;

    /** Agent 唯一标识。 */
    // 将 Java 属性映射到 servers.agent_id 字段。
    @TableField("agent_id")
    private String agentId;

    /** Agent Token 的不可逆哈希。 */
    // 将 Java 属性映射到 Agent Token 哈希字段。
    @TableField("agent_token_hash")
    // 防止 Lombok 生成的 toString 方法输出 Agent Token 哈希。
    @ToString.Exclude
    // 防止 Lombok 生成的 equals 和 hashCode 使用 Agent Token 哈希。
    @EqualsAndHashCode.Exclude
    private String agentTokenHash;

    /** Agent Token 首次创建时间。 */
    // 将 Java 属性映射到 servers.agent_token_created_at 字段，供 Token 生命周期审计使用。
    @TableField("agent_token_created_at")
    private LocalDateTime agentTokenCreatedAt;

    /** Agent Token 最近一次轮换时间。 */
    // 将 Java 属性映射到 servers.agent_token_rotated_at 字段，记录显式轮换操作。
    @TableField("agent_token_rotated_at")
    private LocalDateTime agentTokenRotatedAt;

    /** Agent Token 撤销时间。 */
    // 将 Java 属性映射到 servers.agent_token_revoked_at 字段，记录当前 Token 失效时间。
    @TableField("agent_token_revoked_at")
    private LocalDateTime agentTokenRevokedAt;

    /** Agent 在线状态。 */
    // 将 Java 属性映射到 servers.agent_status 字段。
    @TableField("agent_status")
    private String agentStatus;

    /** 最近一次 Agent 心跳时间。 */
    // 将 Java 属性映射到 servers.last_heartbeat_at 字段。
    @TableField("last_heartbeat_at")
    private LocalDateTime lastHeartbeatAt;

    /** Agent 投递积压条数，来自心跳携带的遥测。 */
    // 将 Java 属性映射到 servers.delivery_pending_count 字段。
    @TableField("delivery_pending_count")
    private Long deliveryPendingCount;

    /** Agent 投递积压字节数，来自心跳携带的遥测。 */
    // 将 Java 属性映射到 servers.delivery_pending_bytes 字段。
    @TableField("delivery_pending_bytes")
    private Long deliveryPendingBytes;

    /** Agent 积压最旧采样时间，来自心跳携带的遥测。 */
    // 将 Java 属性映射到 servers.delivery_oldest_collected_at 字段。
    @TableField("delivery_oldest_collected_at")
    private LocalDateTime deliveryOldestCollectedAt;

    /** Agent 因缓冲满丢弃的采样计数，来自心跳携带的遥测。 */
    // 将 Java 属性映射到 servers.delivery_drop_count 字段。
    @TableField("delivery_drop_count")
    private Long deliveryDropCount;

    /** Agent 本地死信条数，来自心跳携带的遥测。 */
    // 将 Java 属性映射到 servers.delivery_dead_letter_count 字段。
    @TableField("delivery_dead_letter_count")
    private Long deliveryDeadLetterCount;

    /** Agent 本地死信字节数，来自心跳携带的遥测。 */
    // 将 Java 属性映射到 servers.delivery_dead_letter_bytes 字段。
    @TableField("delivery_dead_letter_bytes")
    private Long deliveryDeadLetterBytes;

    /** 软删除标记，0 表示有效，1 表示已删除。 */
    private Boolean deleted;

    /** 软删除发生时间。 */
    // 将 Java 属性映射到 servers.deleted_at 字段。
    @TableField("deleted_at")
    private LocalDateTime deletedAt;

    /** 软删除唯一标识，用于释放有效记录的 host 唯一约束。 */
    // 将 Java 属性映射到 servers.delete_token 字段。
    @TableField("delete_token")
    private String deleteToken;

    /** 记录创建时间。 */
    // 将 Java 属性映射到 servers.created_at 字段。
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 记录最后更新时间。 */
    // 将 Java 属性映射到 servers.updated_at 字段。
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
