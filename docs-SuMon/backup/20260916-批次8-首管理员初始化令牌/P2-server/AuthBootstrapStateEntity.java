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

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
