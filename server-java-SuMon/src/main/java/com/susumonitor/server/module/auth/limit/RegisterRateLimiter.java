package com.susumonitor.server.module.auth.limit;

/**
 * 注册接口防滥用限流契约（2026-08-29）。
 *
 * <p>按客户端 IP 固定窗口计数，超阈值抛 {@code LOGIN_RATE_LIMIT_REACHED(42905)}。
 * 与登录防爆破独立计数（登录侧重防爆破、阈值低；注册侧重防批量轰炸、阈值高），
 * 阈值由 {@code susumonitor.security.register-limit-*} 配置。
 * Redis 启用时由 Redis 实现跨实例共享计数；未启用时由内存实现兜底（默认）。</p>
 */
public interface RegisterRateLimiter {

    /**
     * 记录一次注册尝试并校验是否超限；超限抛业务异常（42905）。
     *
     * @param clientIp 客户端 IP（已解析，非空）
     */
    void checkAttempt(String clientIp);
}
