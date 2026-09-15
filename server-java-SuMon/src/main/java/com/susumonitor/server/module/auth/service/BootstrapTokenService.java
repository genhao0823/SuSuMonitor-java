package com.susumonitor.server.module.auth.service;

import com.susumonitor.server.module.auth.entity.AuthBootstrapStateEntity;

/**
 * 首管理员一次性初始化令牌业务契约（批次 8）。
 *
 * <p>令牌的生命周期：启动装配（生成或载入）→ 启动横幅一次性投递给运维 →
 * 注册时校验 → 首管理员创建事务内消费置空。已初始化实例不再参与任何令牌逻辑。</p>
 */
public interface BootstrapTokenService {

    /**
     * 启动装配：保证首管理员未初始化时状态行存在可用令牌，并经启动横幅投递明文。
     *
     * <p>装配优先级：环境变量预置值（AUTH_BOOTSTRAP_TOKEN，非法长度 fail-fast）>
     * 状态行未消费密文（重启后回显）> 自动生成（SecureRandom 256-bit）。
     * 首管理员已初始化时为空操作。</p>
     */
    void ensureTokenReady();

    /**
     * 校验注册请求携带的候选令牌。
     *
     * @param state 行锁内的初始化状态（须来自 selectForUpdate，保证消费原子性）
     * @param candidateToken 注册请求携带的候选令牌，null/空白视为未携带
     * @throws com.susumonitor.server.common.BusinessException 未携带抛
     *         AUTH_BOOTSTRAP_REQUIRED(40310)；不匹配或状态行无密文抛
     *         AUTH_BOOTSTRAP_TOKEN_INVALID(40311)
     */
    void verifyAgainst(AuthBootstrapStateEntity state, String candidateToken);
}
