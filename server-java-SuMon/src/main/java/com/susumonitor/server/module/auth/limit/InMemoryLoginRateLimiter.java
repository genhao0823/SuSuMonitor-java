package com.susumonitor.server.module.auth.limit;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.config.AppProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 单 JVM 内存版登录防爆破（默认兜底）：每 IP 固定窗口计数。
 *
 * <p>Redis 启用时由 {@link RedisLoginRateLimiter} 取代（互斥条件），计数跨实例共享。</p>
 */
@Component
@ConditionalOnProperty(name = "susumonitor.redis.enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryLoginRateLimiter implements LoginRateLimiter {

    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxAttempts;
    private final long windowSeconds;
    private final Clock clock;

    /** 注入登录限流参数与统一 UTC Clock。 */
    public InMemoryLoginRateLimiter(AppProperties appProperties, Clock clock) {
        this.maxAttempts = appProperties.getSecurity().getLoginLimitMaxAttempts();
        this.windowSeconds = appProperties.getSecurity().getLoginLimitWindowSeconds();
        this.clock = clock;
    }

    /** 记录一次尝试；窗口内计数超过阈值抛 42905。 */
    @Override
    public void checkAttempt(String clientIp) {
        Instant now = Instant.now(clock);
        Window window = windows.compute(clientIp, (ip, existing) -> {
            if (existing == null || !existing.startedAt().plusSeconds(windowSeconds).isAfter(now)) {
                return new Window(now, 1);
            }
            return new Window(existing.startedAt(), existing.count() + 1);
        });
        if (window.count() > maxAttempts) {
            throw new BusinessException(ErrorCode.LOGIN_RATE_LIMIT_REACHED);
        }
    }

    /** 固定窗口状态：窗口起点与已计数。 */
    private record Window(Instant startedAt, int count) {
    }
}
