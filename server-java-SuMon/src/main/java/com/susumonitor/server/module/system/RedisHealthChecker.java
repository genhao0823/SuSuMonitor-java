package com.susumonitor.server.module.system;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 就绪探活（多实例化阶段一，2026-08-17；存活但未就绪语义）。
 *
 * <p>Redis 不可达时返回 false 而非抛异常，由 /api/ready 统一转为 503（50302）；
 * 应用本身不退出，ticket 链路 fail-fast（401）。仅 Redis 启用时注册。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "susumonitor.redis.enabled", havingValue = "true")
public class RedisHealthChecker {

    private final StringRedisTemplate redisTemplate;

    /** 注入自动配置的 StringRedisTemplate。 */
    public RedisHealthChecker(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 探测 Redis 连通性（PING/PONG，连接由模板自动归还）。
     *
     * @return PONG 响应且无异常时为 true
     */
    public boolean isHealthy() {
        try {
            String pong = redisTemplate.execute((RedisCallback<String>) connection -> connection.ping());
            return "PONG".equals(pong);
        } catch (Exception exception) {
            log.warn("redis health check failed: {}", exception.getMessage());
            return false;
        }
    }
}
