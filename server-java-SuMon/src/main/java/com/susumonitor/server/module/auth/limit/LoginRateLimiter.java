package com.susumonitor.server.module.auth.limit;

/**
 * 登录防爆破限流契约（Redis 安全加固一期，2026-08-18）。
 *
 * <p>按客户端 IP 固定窗口计数，超阈值抛 {@code LOGIN_RATE_LIMIT_REACHED(42905)}。
 * Redis 启用时由 Redis 实现跨实例共享计数；未启用时由内存实现兜底（默认）。</p>
 */
public interface LoginRateLimiter {

    /**
     * 记录一次登录尝试并校验是否超限；超限抛业务异常（42905）。
     *
     * @param clientIp 客户端 IP（已解析，非空）
     */
    void checkAttempt(String clientIp);
}
