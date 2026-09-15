package com.susumonitor.server.module.auth.entity;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 映射认证初始化状态，用于在数据库事务中串行化首管理员创建。
 */
// 自动生成初始化状态字段的访问方法及对象基础方法。
@Data
public class AuthBootstrapStateEntity {

    private Long id;

    private Boolean adminInitialized;

    private Long initializedUserId;

    private LocalDateTime initializedAt;

    // 首管理员一次性初始化令牌的 AES-256-GCM 密文信封（批次 8）；
    // 明文永不落库，消费后由 consumeBootstrapToken 置空。
    private String bootstrapTokenCipher;

    // 当前令牌的生成或载入时间（环境变量预置视为载入）。
    private LocalDateTime bootstrapTokenGeneratedAt;

    // 令牌消费时间，即首管理员创建时刻；NULL 表示令牌仍待使用或未签发。
    private LocalDateTime bootstrapTokenConsumedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
