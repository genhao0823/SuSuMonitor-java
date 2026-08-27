package com.susumonitor.server.module.auth.limit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.config.AppProperties;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** 验证 Redis 版登录防爆破：INCR 计数、首次 EXPIRE、超阈值拒绝。 */
class RedisLoginRateLimiterTests {

    private static final String KEY = "susumonitor:login-limit:10.0.0.1";

    private StringRedisTemplate redisTemplate;
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = Mockito.mock(ValueOperations.class);
    private RedisLoginRateLimiter limiter;

    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        AppProperties properties = new AppProperties();
        properties.getSecurity().setLoginLimitMaxAttempts(3);
        properties.getSecurity().setLoginLimitWindowSeconds(60);
        limiter = new RedisLoginRateLimiter(redisTemplate, properties);
    }

    /** 首次计数设置窗口 TTL；窗口内未超阈值放行。 */
    @Test
    void checkAttemptShouldIncrementAndExpireOnFirst() {
        when(valueOps.increment(KEY)).thenReturn(1L, 2L, 3L);

        assertDoesNotThrow(() -> limiter.checkAttempt("10.0.0.1"));
        assertDoesNotThrow(() -> limiter.checkAttempt("10.0.0.1"));
        assertDoesNotThrow(() -> limiter.checkAttempt("10.0.0.1"));

        verify(redisTemplate).expire(eq(KEY), eq(Duration.ofSeconds(60)));
    }

    /** 非首次计数不重复设置 TTL（EXPIRE 幂等，仅在 count==1 时设置）。 */
    @Test
    void checkAttemptShouldNotExpireOnSubsequentCounts() {
        when(valueOps.increment(KEY)).thenReturn(2L);

        assertDoesNotThrow(() -> limiter.checkAttempt("10.0.0.1"));

        verify(redisTemplate, never()).expire(eq(KEY), Mockito.any(Duration.class));
    }

    /** 计数超过阈值抛 42905。 */
    @Test
    void checkAttemptShouldRejectWhenExceeded() {
        when(valueOps.increment(KEY)).thenReturn(4L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> limiter.checkAttempt("10.0.0.1"));
        assertEquals(ErrorCode.LOGIN_RATE_LIMIT_REACHED, exception.getErrorCode());
    }
}
