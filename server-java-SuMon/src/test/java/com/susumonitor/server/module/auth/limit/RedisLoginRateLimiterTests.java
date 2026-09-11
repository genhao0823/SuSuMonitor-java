package com.susumonitor.server.module.auth.limit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.config.AppProperties;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.stubbing.OngoingStubbing;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/** 验证 Redis 版登录防爆破：原子 INCR+EXPIRE 计数、key 与窗口参数、超阈值拒绝。 */
class RedisLoginRateLimiterTests {

    private static final String KEY = "susumonitor:login-limit:10.0.0.1";
    private static final String WINDOW_SECONDS = "60";

    private StringRedisTemplate redisTemplate;
    private RedisLoginRateLimiter limiter;

    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        AppProperties properties = new AppProperties();
        properties.getSecurity().setLoginLimitMaxAttempts(3);
        properties.getSecurity().setLoginLimitWindowSeconds(60);
        limiter = new RedisLoginRateLimiter(redisTemplate, properties);
    }

    /** 窗口内未超阈值放行，脚本以计数 key 与窗口秒数调用。 */
    @Test
    void checkAttemptShouldAllowBelowThreshold() {
        whenExecuteReturns(1L, 2L, 3L);

        assertDoesNotThrow(() -> limiter.checkAttempt("10.0.0.1"));
        assertDoesNotThrow(() -> limiter.checkAttempt("10.0.0.1"));
        assertDoesNotThrow(() -> limiter.checkAttempt("10.0.0.1"));

        verify(redisTemplate, Mockito.times(3)).execute(any(), eq(List.of(KEY)), eq(WINDOW_SECONDS));
    }

    /** 计数超过阈值抛 42905。 */
    @Test
    void checkAttemptShouldRejectWhenExceeded() {
        whenExecuteReturns(4L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> limiter.checkAttempt("10.0.0.1"));
        assertEquals(ErrorCode.LOGIN_RATE_LIMIT_REACHED, exception.getErrorCode());
    }

    /** Redis 返回 null（不可用）时放行，与原实现兜底口径一致。 */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void checkAttemptShouldAllowWhenRedisUnavailable() {
        when(redisTemplate.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(),
                any(Object[].class))).thenReturn(null);

        assertDoesNotThrow(() -> limiter.checkAttempt("10.0.0.1"));
    }

    /** 打桩脚本计数返回序列（首次调用及后续调用依次返回）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void whenExecuteReturns(Long... counts) {
        OngoingStubbing<Long> stubbing = when(redisTemplate.execute(
                ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(Object[].class)));
        stubbing.thenReturn(counts[0], Arrays.copyOfRange(counts, 1, counts.length, Long[].class));
    }
}
