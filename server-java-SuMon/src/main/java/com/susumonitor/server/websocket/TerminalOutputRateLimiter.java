package com.susumonitor.server.websocket;

import com.susumonitor.server.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * 按服务端终端会话限制 Agent 输出的原始字节带宽，防止单个 PTY 会话占满 Monitor 转发通道。
 */
// 注册为 Spring 单例组件，使同一 JVM 内的终端输出共享会话级限流状态。
@Component
public class TerminalOutputRateLimiter {

    private final ConcurrentMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final AppProperties.Terminal terminal;
    private final Clock clock;

    /** 注入终端带宽配置和时钟，使令牌补充可在单元测试中确定性验证。 */
    public TerminalOutputRateLimiter(AppProperties appProperties, Clock clock) {
        this.terminal = appProperties.getTerminal();
        this.clock = clock;
    }

    /**
     * 按 Base64 解码后的原始字节数消耗会话令牌。
     *
     * @param sessionId 服务端生成的终端会话 UUID
     * @param base64Data 已通过协议校验的 Base64 输出数据
     * @return 当前会话有足够令牌时为 true
     */
    public boolean allow(String sessionId, String base64Data) {
        int byteCount = Base64.getDecoder().decode(base64Data).length;
        Instant now = Instant.now(clock);
        return buckets.computeIfAbsent(sessionId, ignored -> new TokenBucket(
                terminal.getOutputRateBytesPerSecond(), terminal.getOutputBurstBytes(), now))
                .tryConsume(byteCount, now);
    }

    /** 会话结束时释放其令牌桶，避免已关闭会话持续占用 JVM 内存。 */
    public void release(String sessionId) {
        buckets.remove(sessionId);
    }

    /** 基于字节速率的同步令牌桶，保证同一会话的并发输出原子计量。 */
    private static final class TokenBucket {

        private final int rateBytesPerSecond;
        private final int capacity;
        private double tokens;
        private Instant refreshedAt;

        /** 创建指定字节速率和容量的令牌桶。 */
        private TokenBucket(int rateBytesPerSecond, int capacity, Instant now) {
            this.rateBytesPerSecond = rateBytesPerSecond;
            this.capacity = capacity;
            this.tokens = capacity;
            this.refreshedAt = now;
        }

        /** 尝试消耗指定字节数的令牌，令牌不足时返回 false。 */
        private synchronized boolean tryConsume(int byteCount, Instant now) {
            long elapsedNanos = Duration.between(refreshedAt, now).toNanos();
            if (elapsedNanos > 0) {
                tokens = Math.min(capacity, tokens + elapsedNanos * (double) rateBytesPerSecond / 1_000_000_000L);
                refreshedAt = now;
            }
            if (tokens < byteCount) {
                return false;
            }
            tokens -= byteCount;
            return true;
        }
    }
}
