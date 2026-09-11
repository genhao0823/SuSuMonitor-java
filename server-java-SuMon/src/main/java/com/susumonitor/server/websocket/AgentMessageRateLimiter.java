package com.susumonitor.server.websocket;

import com.susumonitor.server.common.limit.ClockTimeMeter;
import com.susumonitor.server.config.AppProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * 按认证 WebSocket 会话限制 heartbeat 与 metrics.report，避免高频消息持续占用解析和数据库资源。
 *
 * <p>令牌桶由 Bucket4j 提供：按分钟补充速率匀速回填，突发容量上限吸收瞬时流量。</p>
 */
@Component
public class AgentMessageRateLimiter {

    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);

    private final ConcurrentMap<String, SessionBuckets> sessionBuckets = new ConcurrentHashMap<>();
    private final AppProperties.Agent agent;
    private final ClockTimeMeter timeMeter;

    /** 注入限流参数和可控时钟，令牌补充基于统一 Clock 推进。 */
    public AgentMessageRateLimiter(AppProperties appProperties, Clock clock) {
        this.agent = appProperties.getAgent();
        this.timeMeter = new ClockTimeMeter(clock);
    }

    /** 消耗一次心跳令牌，认证前或已释放会话不得使用该接口。 */
    public boolean allowHeartbeat(String sessionId) {
        return buckets(sessionId).heartbeat().tryConsume(1);
    }

    /** 消耗一次 Metrics 消息令牌，超额时由 Handler 关闭连接。 */
    public boolean allowMetrics(String sessionId) {
        return buckets(sessionId).metrics().tryConsume(1);
    }

    /** 在认证结束或连接关闭时删除会话级限流状态，防止 Map 持续增长。 */
    public void release(String sessionId) {
        sessionBuckets.remove(sessionId);
    }

    /** 获取或创建指定会话的两类消息限流桶。 */
    private SessionBuckets buckets(String sessionId) {
        return sessionBuckets.computeIfAbsent(sessionId, ignored -> new SessionBuckets(
                createBucket(agent.getHeartbeatRatePerMinute(), agent.getHeartbeatBurst()),
                createBucket(agent.getMetricsRatePerMinute(), agent.getMetricsBurst())));
    }

    /** 按分钟补充速率与突发容量创建令牌桶，避免 heartbeat 占用 Metrics 配额。 */
    private Bucket createBucket(int ratePerMinute, int burst) {
        return Bucket.builder()
                .withCustomTimePrecision(timeMeter)
                .addLimit(Bandwidth.classic(burst, Refill.greedy(ratePerMinute, ONE_MINUTE)))
                .build();
    }

    /** 保存一个会话内两类业务消息的独立桶。 */
    private record SessionBuckets(Bucket heartbeat, Bucket metrics) {
    }
}
