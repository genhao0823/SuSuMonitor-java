package com.susumonitor.server.module.auth.limit;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.config.AppProperties;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis 的注册防滥用（2026-08-29）。
 *
 * <p>按客户端 IP 固定窗口计数：{@code INCR susumonitor:register-limit:<ip>}，首次计数时
 * 设置 {@code EXPIRE}（窗口秒数，TTL 自动清理）；计数超过阈值抛 42905。
 * INCR 与 EXPIRE 两步存在极小竞态（并发首次同时设置过期），EXPIRE 幂等可接受。</p>
 */
@Component
@ConditionalOnProperty(name = "susumonitor.redis.enabled", havingValue = "true")
public class RedisRegisterRateLimiter implements RegisterRateLimiter {

    private static final String COUNTER_KEY_PREFIX = "susumonitor:register-limit:";

    private final StringRedisTemplate redisTemplate;
    private final int maxAttempts;
    private final Duration window;

    /** 注入 Redis 模板与注册限流参数。 */
    public RedisRegisterRateLimiter(StringRedisTemplate redisTemplate, AppProperties appProperties) {
        this.redisTemplate = redisTemplate;
        this.maxAttempts = appProperties.getSecurity().getRegisterLimitMaxAttempts();
        this.window = Duration.ofSeconds(appProperties.getSecurity().getRegisterLimitWindowSeconds());
    }

    /** 递增计数并设置窗口 TTL；超过阈值抛 42905。 */
    @Override
    public void checkAttempt(String clientIp) {
        String key = COUNTER_KEY_PREFIX + clientIp;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, window);
        }
        if (count != null && count > maxAttempts) {
            throw new BusinessException(ErrorCode.LOGIN_RATE_LIMIT_REACHED);
        }
    }
}
