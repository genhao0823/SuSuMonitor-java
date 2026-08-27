package com.susumonitor.server.module.auth.limit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 验证内存版登录防爆破：窗口内阈值、窗口重置、IP 隔离。 */
class InMemoryLoginRateLimiterTests {

    private static final Instant FIXED = Instant.parse("2026-08-18T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);

    private InMemoryLoginRateLimiter limiter;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getSecurity().setLoginLimitMaxAttempts(3);
        properties.getSecurity().setLoginLimitWindowSeconds(60);
        limiter = new InMemoryLoginRateLimiter(properties, CLOCK);
    }

    /** 窗口内第 N 次尝试放行（N=阈值），超过阈值抛 42905。 */
    @Test
    void checkAttemptShouldRejectWhenWindowExceeded() {
        assertDoesNotThrow(() -> limiter.checkAttempt("10.0.0.1"));
        assertDoesNotThrow(() -> limiter.checkAttempt("10.0.0.1"));
        assertDoesNotThrow(() -> limiter.checkAttempt("10.0.0.1"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> limiter.checkAttempt("10.0.0.1"));
        assertEquals(ErrorCode.LOGIN_RATE_LIMIT_REACHED, exception.getErrorCode());
    }

    /** 窗口过期后计数重置，再次尝试放行。 */
    @Test
    void checkAttemptShouldResetAfterWindowElapses() {
        // 用一个可推进的时钟：窗口 60s，推进 61s 后窗口过期。
        MutableClock clock = new MutableClock(FIXED);
        AppProperties properties = new AppProperties();
        properties.getSecurity().setLoginLimitMaxAttempts(1);
        properties.getSecurity().setLoginLimitWindowSeconds(60);
        InMemoryLoginRateLimiter windowLimiter = new InMemoryLoginRateLimiter(properties, clock);

        windowLimiter.checkAttempt("10.0.0.1");
        assertThrows(BusinessException.class, () -> windowLimiter.checkAttempt("10.0.0.1"));

        clock.advance(Duration.ofSeconds(61));
        assertDoesNotThrow(() -> windowLimiter.checkAttempt("10.0.0.1"));
    }

    /** 不同 IP 各自独立计数。 */
    @Test
    void checkAttemptShouldIsolateByIp() {
        for (int i = 0; i < 3; i++) {
            limiter.checkAttempt("10.0.0.1");
        }
        assertDoesNotThrow(() -> limiter.checkAttempt("10.0.0.2"));
        assertThrows(BusinessException.class, () -> limiter.checkAttempt("10.0.0.1"));
    }

    /** 可推进的测试时钟。 */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
