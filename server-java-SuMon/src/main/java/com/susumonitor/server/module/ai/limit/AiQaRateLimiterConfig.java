package com.susumonitor.server.module.ai.limit;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.common.limit.FixedWindowRateLimiter;
import com.susumonitor.server.common.limit.RedisFixedWindowRateLimiter;
import com.susumonitor.server.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 运维问答限流装配：仅在 AI 启用时加载（与 AiQaService 同条件，避免配置组合下缺 Bean），
 * 并按 Redis 开关互斥选择实现；参数读取 {@code susumonitor.ai.qa.rate-limit-*}。
 *
 * <p>与诊断限流（{@link AiRateLimiterConfig}）独立计数：问答与诊断各自窗口，
 * 互不挤占。窗口/阈值逻辑统一由 common/limit 的固定窗口实现承担
 * （Bucket4j / 原子 Lua）。</p>
 */
@Configuration
@ConditionalOnProperty(name = "susumonitor.ai.enabled", havingValue = "true")
public class AiQaRateLimiterConfig {

    /** 单 JVM 内存版按管理员固定窗口限流（Redis 未启用时的默认实现）。 */
    @Bean
    @ConditionalOnProperty(name = "susumonitor.redis.enabled", havingValue = "false", matchIfMissing = true)
    public AiQaRateLimiter inMemoryAiQaRateLimiter(AppProperties appProperties, Clock clock) {
        return new InMemoryAiQaRateLimiter(appProperties, clock);
    }

    /** Redis 版按管理员固定窗口限流（Redis 启用时注册，计数跨实例共享）。 */
    @Bean
    @ConditionalOnProperty(name = "susumonitor.redis.enabled", havingValue = "true")
    public AiQaRateLimiter redisAiQaRateLimiter(
            org.springframework.data.redis.core.StringRedisTemplate redisTemplate, AppProperties appProperties) {
        return new RedisAiQaRateLimiter(redisTemplate, appProperties);
    }

    /** 单 JVM 内存实现：每 {@code actorId} 固定窗口计数，窗口按首次访问时间起点整量补充。 */
    static class InMemoryAiQaRateLimiter implements AiQaRateLimiter {

        private final FixedWindowRateLimiter window;

        /** 注入问答限流参数与统一 UTC Clock。 */
        InMemoryAiQaRateLimiter(AppProperties appProperties, Clock clock) {
            this.window = new FixedWindowRateLimiter(
                    appProperties.getAi().getQa().getRateLimitMaxRequests(),
                    Duration.ofSeconds(appProperties.getAi().getQa().getRateLimitWindowSeconds()), clock);
        }

        /** 记录一次尝试；窗口内计数超过阈值抛 42906。 */
        @Override
        public void checkAllowed(Long actorId) {
            if (!window.tryAcquire(String.valueOf(actorId))) {
                throw new BusinessException(ErrorCode.AI_RATE_LIMIT_REACHED);
            }
        }
    }

    /**
     * Redis 实现：{@code INCR susumonitor:ai-qa-limit:<actorId>}（原子 Lua），
     * 首次计数原子设置窗口 TTL，计数跨实例共享。
     */
    static class RedisAiQaRateLimiter implements AiQaRateLimiter {

        private final RedisFixedWindowRateLimiter window;

        /** 注入 Redis 模板与问答限流参数。 */
        RedisAiQaRateLimiter(org.springframework.data.redis.core.StringRedisTemplate redisTemplate,
                AppProperties appProperties) {
            this.window = new RedisFixedWindowRateLimiter(redisTemplate, "susumonitor:ai-qa-limit:",
                    appProperties.getAi().getQa().getRateLimitMaxRequests(),
                    appProperties.getAi().getQa().getRateLimitWindowSeconds());
        }

        /** 原子递增计数并保证首窗口 TTL；超过阈值抛 42906。 */
        @Override
        public void checkAllowed(Long actorId) {
            if (!window.tryAcquire(String.valueOf(actorId))) {
                throw new BusinessException(ErrorCode.AI_RATE_LIMIT_REACHED);
            }
        }
    }
}
