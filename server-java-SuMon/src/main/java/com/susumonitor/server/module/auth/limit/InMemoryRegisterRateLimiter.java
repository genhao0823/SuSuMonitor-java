package com.susumonitor.server.module.auth.limit;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.common.limit.FixedWindowRateLimiter;
import com.susumonitor.server.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 单 JVM 内存版注册防滥用（默认兜底）：每 IP 固定窗口计数（Bucket4j 实现）。
 *
 * <p>Redis 启用时由 {@link RedisRegisterRateLimiter} 取代（互斥条件），计数跨实例共享。</p>
 */
@Component
@ConditionalOnProperty(name = "susumonitor.redis.enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryRegisterRateLimiter implements RegisterRateLimiter {

    private final FixedWindowRateLimiter window;

    /** 注入注册限流参数与统一 UTC Clock。 */
    public InMemoryRegisterRateLimiter(AppProperties appProperties, Clock clock) {
        this.window = new FixedWindowRateLimiter(
                appProperties.getSecurity().getRegisterLimitMaxAttempts(),
                Duration.ofSeconds(appProperties.getSecurity().getRegisterLimitWindowSeconds()), clock);
    }

    /** 记录一次尝试；窗口内计数超过阈值抛 42905。 */
    @Override
    public void checkAttempt(String clientIp) {
        if (!window.tryAcquire(clientIp)) {
            throw new BusinessException(ErrorCode.LOGIN_RATE_LIMIT_REACHED);
        }
    }
}
