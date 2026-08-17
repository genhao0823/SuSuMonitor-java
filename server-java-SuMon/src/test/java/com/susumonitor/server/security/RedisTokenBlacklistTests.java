package com.susumonitor.server.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** 验证 JWT 黑名单的吊销写入（SET EX）与命中检查（hasKey）。 */
class RedisTokenBlacklistTests {

    private static final String KEY_PREFIX = "susumonitor:jwt-blacklist:";

    private StringRedisTemplate redisTemplate;
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = Mockito.mock(ValueOperations.class);
    private RedisTokenBlacklist blacklist;

    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        blacklist = new RedisTokenBlacklist(redisTemplate);
    }

    /** 吊销：按 jti 写入黑名单 key，TTL 精确到 token 剩余有效期。 */
    @Test
    void revokeShouldStoreTokenIdWithTtl() {
        blacklist.revoke("token-id", Duration.ofSeconds(30));

        verify(valueOps).set(eq(KEY_PREFIX + "token-id"), eq("1"), eq(Duration.ofSeconds(30)));
    }

    /** 命中检查：黑名单中存在 → true。 */
    @Test
    void isRevokedShouldReturnTrueWhenBlacklisted() {
        when(redisTemplate.hasKey(KEY_PREFIX + "token-id")).thenReturn(true);

        assertTrue(blacklist.isRevoked("token-id"));
    }

    /** 未命中（未登出/TTL 已过期自动清除）→ false。 */
    @Test
    void isRevokedShouldReturnFalseWhenNotBlacklisted() {
        when(redisTemplate.hasKey(KEY_PREFIX + "token-id")).thenReturn(false);

        assertFalse(blacklist.isRevoked("token-id"));
    }
}
