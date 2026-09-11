package com.susumonitor.server.websocket;

import com.susumonitor.server.common.limit.ClockTimeMeter;
import com.susumonitor.server.config.AppProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * 按服务端终端会话限制 Agent 输出的原始字节带宽，防止单个 PTY 会话占满 Monitor 转发通道。
 *
 * <p>字节令牌桶由 Bucket4j 提供：按秒补充速率匀速回填，突发容量上限吸收瞬时输出。</p>
 */
// 注册为 Spring 单例组件，使同一 JVM 内的终端输出共享会话级限流状态。
@Component
public class TerminalOutputRateLimiter {

    private static final Duration ONE_SECOND = Duration.ofSeconds(1);

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AppProperties.Terminal terminal;
    private final ClockTimeMeter timeMeter;

    /** 注入终端带宽配置和时钟，使令牌补充可在单元测试中确定性验证。 */
    public TerminalOutputRateLimiter(AppProperties appProperties, Clock clock) {
        this.terminal = appProperties.getTerminal();
        this.timeMeter = new ClockTimeMeter(clock);
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
        return buckets.computeIfAbsent(sessionId, ignored -> newBucket())
                .tryConsume(byteCount);
    }

    /** 会话结束时释放其令牌桶，避免已关闭会话持续占用 JVM 内存。 */
    public void release(String sessionId) {
        buckets.remove(sessionId);
    }

    /** 按秒级字节补充速率与突发字节容量创建 Bucket4j 字节令牌桶。 */
    private Bucket newBucket() {
        return Bucket.builder()
                .withCustomTimePrecision(timeMeter)
                .addLimit(Bandwidth.classic(terminal.getOutputBurstBytes(),
                        Refill.greedy(terminal.getOutputRateBytesPerSecond(), ONE_SECOND)))
                .build();
    }
}
