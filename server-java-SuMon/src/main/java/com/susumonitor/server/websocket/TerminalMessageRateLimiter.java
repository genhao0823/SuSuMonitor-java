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
 * 按 Monitor WebSocket 和终端会话限制控制帧，避免单个浏览器会话耗尽 Agent 和 Java 的转发资源。
 *
 * <p>令牌桶由 Bucket4j 提供：按分钟补充速率匀速回填，突发容量上限吸收瞬时流量。</p>
 */
@Component
public class TerminalMessageRateLimiter {

    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);
    private static final String OPEN_SCOPE = "open";
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AppProperties.Terminal terminal;
    private final ClockTimeMeter timeMeter;

    /** 注入终端限流配置和时钟，使分钟级补充规则可被确定性测试。 */
    public TerminalMessageRateLimiter(AppProperties appProperties, Clock clock) {
        this.terminal = appProperties.getTerminal();
        this.timeMeter = new ClockTimeMeter(clock);
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
        return buckets.computeIfAbsent(key, ignored -> createBucket(message.type()))
                .tryConsume(1);
    }

    /** 在浏览器连接关闭时清理所有关联桶，避免断开会话持续占用 JVM 内存。 */
    public void release(MonitorWebSocketSession monitorSession) {
        String prefix = monitorSession.socketSession().getId() + ':';
        buckets.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** 按冻结的四种控制帧参数创建互相隔离的令牌桶。 */
    private Bucket createBucket(String type) {
        return switch (type) {
            case "terminal.open" -> newBucket(terminal.getOpenRatePerMinute(), terminal.getOpenBurst());
            case "terminal.input" -> newBucket(terminal.getInputRatePerMinute(), terminal.getInputBurst());
            case "terminal.resize" -> newBucket(terminal.getResizeRatePerMinute(), terminal.getResizeBurst());
            case "terminal.close" -> newBucket(terminal.getCloseRatePerMinute(), terminal.getCloseBurst());
            default -> throw new IllegalArgumentException("Unsupported terminal control message type");
        };
    }

    /** 按分钟补充速率与突发容量创建 Bucket4j 令牌桶。 */
    private Bucket newBucket(int ratePerMinute, int burst) {
        return Bucket.builder()
                .withCustomTimePrecision(timeMeter)
                .addLimit(Bandwidth.classic(burst, Refill.greedy(ratePerMinute, ONE_MINUTE)))
                .build();
    }
}
