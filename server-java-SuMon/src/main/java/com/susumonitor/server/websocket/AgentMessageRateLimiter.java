package com.susumonitor.server.websocket;

import com.susumonitor.server.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * 按认证 WebSocket 会话限制 heartbeat 与 metrics.report，避免高频消息持续占用解析和数据库资源。
 */
@Component
public class AgentMessageRateLimiter {

    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);
    private final ConcurrentMap<String, SessionBuckets> sessionBuckets = new ConcurrentHashMap<>();
    private final AppProperties.Agent agent;
    private final Clock clock;

    /** 注入限流参数和可控时钟，令牌补充基于单调的 Instant 差值计算。 */
    public AgentMessageRateLimiter(AppProperties appProperties, Clock clock) {
        this.agent = appProperties.getAgent();
        this.clock = clock;
    }

    /** 消耗一次心跳令牌，认证前或已释放会话不得使用该接口。 */
    public boolean allowHeartbeat(String sessionId) {
        return buckets(sessionId).heartbeat().tryConsume(Instant.now(clock));
    }

    /** 消耗一次 Metrics 消息令牌，超额时由 Handler 关闭连接。 */
    public boolean allowMetrics(String sessionId) {
        return buckets(sessionId).metrics().tryConsume(Instant.now(clock));
    }

    /** 在认证结束或连接关闭时删除会话级限流状态，防止 Map 持续增长。 */
    public void release(String sessionId) {
        sessionBuckets.remove(sessionId);
    }

    /** 获取或创建指定会话的两类消息限流桶。 */
    private SessionBuckets buckets(String sessionId) {
        return sessionBuckets.computeIfAbsent(sessionId, ignored -> new SessionBuckets(
                new TokenBucket(agent.getHeartbeatRatePerMinute(), agent.getHeartbeatBurst(), Instant.now(clock)),
                new TokenBucket(agent.getMetricsRatePerMinute(), agent.getMetricsBurst(), Instant.now(clock))));
    }

    /** 保存一个会话内两类业务消息的独立桶，避免 heartbeat 占用 Metrics 配额。 */
    private record SessionBuckets(TokenBucket heartbeat, TokenBucket metrics) {
    }

    /** 基于分钟补充速率的同步 Token Bucket；每个桶仅被对应会话并发消息访问。 */
    private static final class TokenBucket {

        private final int ratePerMinute;
        private final int capacity;
        private double tokens;
        private Instant refreshedAt;

        /** 创建指定分钟速率和容量的令牌桶。 */
        private TokenBucket(int ratePerMinute, int capacity, Instant now) {
            this.ratePerMinute = ratePerMinute;
            this.capacity = capacity;
            this.tokens = capacity;
            this.refreshedAt = now;
        }

        /** 尝试消耗一个令牌，令牌不足时返回 false。 */
        private synchronized boolean tryConsume(Instant now) {
            long elapsedMillis = Duration.between(refreshedAt, now).toMillis();
            if (elapsedMillis > 0) {
                tokens = Math.min(capacity, tokens + elapsedMillis * (double) ratePerMinute / ONE_MINUTE.toMillis());
                refreshedAt = now;
            }
            if (tokens < 1) {
                return false;
            }
            tokens--;
            return true;
        }
    }
}
