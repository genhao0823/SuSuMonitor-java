package com.susumonitor.server.security;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis 的 JWT 黑名单（Redis 安全加固一期，2026-08-18）。
 *
 * <p>登出时按 jti（tokenId）写入黑名单，TTL 精确到该 token 的剩余有效期；
 * 任意实例在过滤器阶段校验黑名单，实现"登出真实失效、跨实例生效"。
 * Redis 未启用（REDIS_ENABLED=false）时本组件不注册，logout 保持空操作（与现状一致）。</p>
 */
@Component
@ConditionalOnProperty(name = "susumonitor.redis.enabled", havingValue = "true")
public class RedisTokenBlacklist {

    private static final String BLACKLIST_KEY_PREFIX = "susumonitor:jwt-blacklist:";

    private final StringRedisTemplate redisTemplate;

    /** 注入自动配置的 StringRedisTemplate。 */
    public RedisTokenBlacklist(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 将 tokenId 加入黑名单，TTL 为 token 剩余有效期（到期自动清除，无需定时任务）。
     *
     * @param tokenId JWT jti
     * @param ttl     剩余有效期
     */
    public void revoke(String tokenId, Duration ttl) {
        redisTemplate.opsForValue().set(BLACKLIST_KEY_PREFIX + tokenId, "1", ttl);
    }

    /**
     * 检查 tokenId 是否已被吊销。
     *
     * @param tokenId JWT jti
     * @return 黑名单命中时为 true
     */
    public boolean isRevoked(String tokenId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_KEY_PREFIX + tokenId));
    }
}
