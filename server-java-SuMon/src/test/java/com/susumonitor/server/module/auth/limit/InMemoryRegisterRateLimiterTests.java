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

/** 验证内存版注册防滥用：窗口内阈值、窗口重置、IP 隔离。 */
class InMemoryRegisterRateLimiterTests {

    private static final Instant FIXED = Instant.parse("2026-08-29T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);

    private InMemoryRegisterRateLimiter limiter;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getSecurity().setRegisterLimitMaxAttempts(3);
        properties.getSecurity().setRegisterLimitWindowSeconds(60);
        limiter = new InMemoryRegisterRateLimiter(properties, CLOCK);
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
        MutableClock clock = new MutableClock(FIXED);
        AppProperties properties = new AppProperties();
        properties.getSecurity().setRegisterLimitMaxAttempts(1);
        properties.getSecurity().setRegisterLimitWindowSeconds(60);
        InMemoryRegisterRateLimiter windowLimiter = new InMemoryRegisterRateLimiter(properties, clock);

        windowLimiter.checkAttempt("10.0.0.1");
        assertThrows(BusinessException.class, () -> windowLimiter.checkAttempt("10.0.0.1"));

        clock.advance(Duration.ofSeconds(61));
        assertDoesNotThrow(() -> windowLimiter.checkAttempt("10.0.0.1"));
    }

    /** 不同 IP 独立计数，互不影响。 */
    @Test
    void checkAttemptShouldIsolateByIp() {
        AppProperties properties = new AppProperties();
        properties.getSecurity().setRegisterLimitMaxAttempts(1);
        properties.getSecurity().setRegisterLimitWindowSeconds(60);
        InMemoryRegisterRateLimiter ipLimiter = new InMemoryRegisterRateLimiter(properties, CLOCK);

        ipLimiter.checkAttempt("10.0.0.1");
        assertThrows(BusinessException.class, () -> ipLimiter.checkAttempt("10.0.0.1"));
        assertDoesNotThrow(() -> ipLimiter.checkAttempt("10.0.0.2"));
    }

    /** 测试用可推进时钟：固定起点，按需推进。 */
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
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
