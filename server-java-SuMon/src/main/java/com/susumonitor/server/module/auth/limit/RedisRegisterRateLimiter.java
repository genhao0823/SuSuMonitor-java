package com.susumonitor.server.module.auth.limit;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.common.limit.RedisFixedWindowRateLimiter;
import com.susumonitor.server.config.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis 的注册防滥用（2026-08-29）。
 *
 * <p>按客户端 IP 固定窗口原子计数：{@code INCR susumonitor:register-limit:<ip>}，
 * 首次计数原子设置 {@code EXPIRE}（窗口秒数，TTL 自动清理）；计数超过阈值抛 42905。</p>
 */
@Component
@ConditionalOnProperty(name = "susumonitor.redis.enabled", havingValue = "true")
public class RedisRegisterRateLimiter implements RegisterRateLimiter {

    private final RedisFixedWindowRateLimiter window;

    /** 注入 Redis 模板与注册限流参数。 */
    public RedisRegisterRateLimiter(StringRedisTemplate redisTemplate, AppProperties appProperties) {
        this.window = new RedisFixedWindowRateLimiter(redisTemplate, "susumonitor:register-limit:",
                appProperties.getSecurity().getRegisterLimitMaxAttempts(),
                appProperties.getSecurity().getRegisterLimitWindowSeconds());
    }

    /** 原子递增计数并保证首窗口 TTL；超过阈值抛 42905。 */
    @Override
    public void checkAttempt(String clientIp) {
        if (!window.tryAcquire(clientIp)) {
            throw new BusinessException(ErrorCode.LOGIN_RATE_LIMIT_REACHED);
        }
    }
}
