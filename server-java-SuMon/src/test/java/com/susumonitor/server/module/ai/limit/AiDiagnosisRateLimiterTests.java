package com.susumonitor.server.module.ai.limit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 验证 AI 诊断按管理员固定窗口限流的上限、窗口推进与维度隔离。 */
class AiDiagnosisRateLimiterTests {

    private static final Instant START = Instant.parse("2026-09-02T00:00:00Z");
    private static final Long ACTOR_A = 1L;
    private static final Long ACTOR_B = 2L;

    private AppProperties appProperties;
    private MutableClock clock;
    private AiDiagnosisRateLimiter limiter;

    /** 每个用例重建限流器：窗口 60 秒、上限 3 次，使用可控时钟。 */
    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getAi().setRateLimitMaxRequests(3);
        appProperties.getAi().setRateLimitWindowSeconds(60);
        clock = new MutableClock(START);
        limiter = new AiRateLimiterConfig.InMemoryAiDiagnosisRateLimiter(appProperties, clock);
    }

    /** 窗口内超过上限的第 N+1 次请求抛 42906。 */
    @Test
    void shouldRejectWhenWindowLimitExceeded() {
        assertDoesNotThrow(() -> limiter.checkAllowed(ACTOR_A));
        assertDoesNotThrow(() -> limiter.checkAllowed(ACTOR_A));
        assertDoesNotThrow(() -> limiter.checkAllowed(ACTOR_A));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> limiter.checkAllowed(ACTOR_A));

        assertEquals(ErrorCode.AI_RATE_LIMIT_REACHED, exception.getErrorCode());
    }

    /** 窗口推进后计数重置，允许新的配额。 */
    @Test
    void shouldResetCounterAfterWindowAdvance() {
        assertDoesNotThrow(() -> limiter.checkAllowed(ACTOR_A));
        assertDoesNotThrow(() -> limiter.checkAllowed(ACTOR_A));
        assertDoesNotThrow(() -> limiter.checkAllowed(ACTOR_A));
        assertThrows(BusinessException.class, () -> limiter.checkAllowed(ACTOR_A));

        clock.advance(Duration.ofSeconds(61));

        assertDoesNotThrow(() -> limiter.checkAllowed(ACTOR_A));
    }

    /** 窗口边界内推进（未满窗口秒数）不重置计数。 */
    @Test
    void shouldKeepCounterInsideSameWindow() {
        assertDoesNotThrow(() -> limiter.checkAllowed(ACTOR_A));
        assertDoesNotThrow(() -> limiter.checkAllowed(ACTOR_A));
        assertDoesNotThrow(() -> limiter.checkAllowed(ACTOR_A));

        clock.advance(Duration.ofSeconds(59));

        assertThrows(BusinessException.class, () -> limiter.checkAllowed(ACTOR_A));
    }

    /** 不同管理员的窗口计数相互隔离。 */
    @Test
    void shouldIsolateCountersPerActor() {
        assertDoesNotThrow(() -> limiter.checkAllowed(ACTOR_A));
        assertDoesNotThrow(() -> limiter.checkAllowed(ACTOR_A));
        assertDoesNotThrow(() -> limiter.checkAllowed(ACTOR_A));

        assertDoesNotThrow(() -> limiter.checkAllowed(ACTOR_B));
        BusinessException exception = assertThrows(BusinessException.class,
                () -> limiter.checkAllowed(ACTOR_A));

        assertEquals(ErrorCode.AI_RATE_LIMIT_REACHED, exception.getErrorCode());
    }

    /** 可推进的测试时钟，模拟窗口滑动与跨窗口重置。 */
    static final class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
