package com.susumonitor.server.module.ai.limit;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.config.AppProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 只读诊断限流装配：仅在 AI 启用时加载，并按 Redis 开关互斥选择实现。
 *
 * <p>类级条件保证 AI 关闭时不装配任何限流 Bean（零额外依赖）；方法级条件
 * 复用注册限流的互斥约定：Redis 关闭（默认）用单 JVM 内存实现，开启时用
 * Redis 计数以支持跨实例共享。</p>
 */
@Configuration
@ConditionalOnProperty(name = "susumonitor.ai.enabled", havingValue = "true")
public class AiRateLimiterConfig {

    /**
     * 单 JVM 内存版按管理员固定窗口限流（Redis 未启用时的默认实现）。
     *
     * @param appProperties 应用配置
     * @param clock 统一 UTC 时钟
     * @return 内存限流器
     */
    // Redis 未启用（含缺省）时注册内存实现，与 Redis 实现严格互斥。
    @Bean
    @ConditionalOnProperty(name = "susumonitor.redis.enabled", havingValue = "false", matchIfMissing = true)
    public AiDiagnosisRateLimiter inMemoryAiDiagnosisRateLimiter(AppProperties appProperties, Clock clock) {
        return new InMemoryAiDiagnosisRateLimiter(appProperties, clock);
    }

    /**
     * Redis 版按管理员固定窗口限流（Redis 启用时注册，计数跨实例共享）。
     *
     * @param redisTemplate Redis 字符串模板
     * @param appProperties 应用配置
     * @return Redis 限流器
     */
    // Redis 启用时注册 Redis 实现，覆盖内存实现。
    @Bean
    @ConditionalOnProperty(name = "susumonitor.redis.enabled", havingValue = "true")
    public AiDiagnosisRateLimiter redisAiDiagnosisRateLimiter(
            org.springframework.data.redis.core.StringRedisTemplate redisTemplate, AppProperties appProperties) {
        return new RedisAiDiagnosisRateLimiter(redisTemplate, appProperties);
    }

    /**
     * 单 JVM 内存实现：每 {@code actorId} 固定窗口计数，窗口按首次访问时间起点滑动重置。
     */
    static class InMemoryAiDiagnosisRateLimiter implements AiDiagnosisRateLimiter {

        private final ConcurrentMap<Long, Window> windows = new ConcurrentHashMap<>();
        private final int maxRequests;
        private final long windowSeconds;
        private final Clock clock;

        /** 注入 AI 限流参数与统一 UTC Clock。 */
        InMemoryAiDiagnosisRateLimiter(AppProperties appProperties, Clock clock) {
            this.maxRequests = appProperties.getAi().getRateLimitMaxRequests();
            this.windowSeconds = appProperties.getAi().getRateLimitWindowSeconds();
            this.clock = clock;
        }

        /** 记录一次尝试；窗口内计数超过阈值抛 42906。 */
        @Override
        public void checkAllowed(Long actorId) {
            Instant now = Instant.now(clock);
            Window window = windows.compute(actorId, (id, existing) -> {
                if (existing == null || !existing.startedAt().plusSeconds(windowSeconds).isAfter(now)) {
                    return new Window(now, 1);
                }
                return new Window(existing.startedAt(), existing.count() + 1);
            });
            if (window.count() > maxRequests) {
                throw new BusinessException(ErrorCode.AI_RATE_LIMIT_REACHED);
            }
        }

        /** 固定窗口状态：窗口起点与已计数。 */
        private record Window(Instant startedAt, int count) {
        }
    }

    /**
     * Redis 实现：{@code INCR susumonitor:ai-diagnosis-limit:<actorId>}，首次计数设置窗口 TTL。
     *
     * <p>INCR 与 EXPIRE 存在与注册限流相同的极小首窗口竞态，EXPIRE 幂等可接受。</p>
     */
    static class RedisAiDiagnosisRateLimiter implements AiDiagnosisRateLimiter {

        private static final String COUNTER_KEY_PREFIX = "susumonitor:ai-diagnosis-limit:";

        private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
        private final int maxRequests;
        private final java.time.Duration window;

        /** 注入 Redis 模板与 AI 限流参数。 */
        RedisAiDiagnosisRateLimiter(org.springframework.data.redis.core.StringRedisTemplate redisTemplate,
                AppProperties appProperties) {
            this.redisTemplate = redisTemplate;
            this.maxRequests = appProperties.getAi().getRateLimitMaxRequests();
            this.window = java.time.Duration.ofSeconds(appProperties.getAi().getRateLimitWindowSeconds());
        }

        /** 递增计数并设置窗口 TTL；超过阈值抛 42906。 */
        @Override
        public void checkAllowed(Long actorId) {
            String key = COUNTER_KEY_PREFIX + actorId;
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1) {
                redisTemplate.expire(key, window);
            }
            if (count != null && count > maxRequests) {
                throw new BusinessException(ErrorCode.AI_RATE_LIMIT_REACHED);
            }
        }
    }
}
