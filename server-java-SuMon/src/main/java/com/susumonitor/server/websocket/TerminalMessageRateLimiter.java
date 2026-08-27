package com.susumonitor.server.websocket;

import com.susumonitor.server.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * 按 Monitor WebSocket 和终端会话限制控制帧，避免单个浏览器会话耗尽 Agent 和 Java 的转发资源。
 */
@Component
public class TerminalMessageRateLimiter {

    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);
    private static final String OPEN_SCOPE = "open";
    private final ConcurrentMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final AppProperties.Terminal terminal;
    private final Clock clock;

    /** 注入终端限流配置和时钟，使分钟级补充规则可被确定性测试。 */
    public TerminalMessageRateLimiter(AppProperties appProperties, Clock clock) {
        this.terminal = appProperties.getTerminal();
        this.clock = clock;
    }

    /**
     * 消耗当前控制帧对应的令牌。
     *
     * <p>open 尚未分配服务端 session_id，按 Monitor WebSocket 独立限流；其他帧按 Monitor WebSocket 与
     * 已校验 session_id 分桶，互不占用令牌。</p>
     *
     * @param monitorSession 已认证的浏览器 WebSocket 会话
     * @param message 已通过协议校验的终端控制帧
     * @return 有可用令牌时为 true
     */
    public boolean allow(MonitorWebSocketSession monitorSession, TerminalMessage message) {
        String scope = TerminalMessageType.TERMINAL_OPEN.value().equals(message.type())
                ? OPEN_SCOPE : message.payload().path("session_id").textValue();
        String key = monitorSession.socketSession().getId() + ':' + message.type() + ':' + scope;
        return buckets.computeIfAbsent(key, ignored -> createBucket(message.type(), Instant.now(clock)))
                .tryConsume(Instant.now(clock));
    }

    /** 在浏览器连接关闭时清理所有关联桶，避免断开会话持续占用 JVM 内存。 */
    public void release(MonitorWebSocketSession monitorSession) {
        String prefix = monitorSession.socketSession().getId() + ':';
        buckets.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** 按冻结的四种控制帧参数创建互相隔离的令牌桶。 */
    private TokenBucket createBucket(String type, Instant now) {
        return switch (type) {
            case "terminal.open" -> new TokenBucket(terminal.getOpenRatePerMinute(), terminal.getOpenBurst(), now);
            case "terminal.input" -> new TokenBucket(terminal.getInputRatePerMinute(), terminal.getInputBurst(), now);
            case "terminal.resize" -> new TokenBucket(terminal.getResizeRatePerMinute(), terminal.getResizeBurst(), now);
            case "terminal.close" -> new TokenBucket(terminal.getCloseRatePerMinute(), terminal.getCloseBurst(), now);
            default -> throw new IllegalArgumentException("Unsupported terminal control message type");
        };
    }

    /** 基于分钟补充速率的同步令牌桶，单桶可安全处理同一 WebSocket 的并发消息。 */
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
