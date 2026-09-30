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

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String username;

    @TableField("password_hash")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private String passwordHash;

    private String role;

    @TableField("review_status")
    private String reviewStatus;

    @TableField("reviewed_by")
    private Long reviewedBy;

    @TableField("reviewed_at")
    private LocalDateTime reviewedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
