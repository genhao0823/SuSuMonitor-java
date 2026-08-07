package com.susumonitor.server.module.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 用户实体，映射 users 数据库表，承载注册、登录和审核所需的核心字段。
 *
 * <p>密码哈希排除在 toString、equals 和 hashCode 之外，防止敏感信息泄露。</p>
 */
@TableName("users")
@Data
public class UserEntity {

    // 将 id 映射为数据库主键，并使用数据库自增策略生成新用户 ID。
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String username;

    // 将 Java 的 passwordHash 属性映射到 users.password_hash 字段。
    @TableField("password_hash")
    // 防止 Lombok 生成的 toString 方法输出 BCrypt 密码哈希。
    @ToString.Exclude
    // 防止 Lombok 生成的 equals 和 hashCode 使用密码哈希参与对象比较。
    @EqualsAndHashCode.Exclude
    private String passwordHash;

    private String role;

    // 将 Java 的 reviewStatus 属性映射到 users.review_status 字段。
    @TableField("review_status")
    private String reviewStatus;

    // 将 Java 的 reviewedBy 属性映射到 users.reviewed_by 字段。
    @TableField("reviewed_by")
    private Long reviewedBy;

    // 将 Java 的 reviewedAt 属性映射到 users.reviewed_at 字段。
    @TableField("reviewed_at")
    private LocalDateTime reviewedAt;

    // 将 Java 的 createdAt 属性映射到 users.created_at 字段。
    @TableField("created_at")
    private LocalDateTime createdAt;

    // 将 Java 的 updatedAt 属性映射到 users.updated_at 字段。
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
