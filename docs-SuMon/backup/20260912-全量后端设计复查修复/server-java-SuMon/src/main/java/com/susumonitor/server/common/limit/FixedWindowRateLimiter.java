package com.susumonitor.server.common.limit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 单 JVM 固定窗口限流（Bucket4j 实现）：每个 key 一个令牌桶，窗口起点为该 key
 * 首次访问时间，每窗口整量补充 maxRequests 个令牌，超配额的尝试被拒绝。
 *
 * <p>登录/注册防爆破、AI 诊断/问答与命令配额等按 key 计数场景共用本实现，
 * 仅配置参数与错误码不同；线程安全由 Bucket4j 本地桶保证。</p>
 */
public class FixedWindowRateLimiter {

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final long maxRequests;
    private final Duration window;
    private final Clock clock;

    /** 创建固定窗口限流器；clock 注入统一 UTC 时钟，测试可用可推进时钟。 */
    public FixedWindowRateLimiter(long maxRequests, Duration window, Clock clock) {
        this.maxRequests = maxRequests;
        this.window = window;
        this.clock = clock;
    }

    /**
     * 为 key 记录一次尝试（检查即计数）。
     *
     * @param key 限流维度标识（如客户端 IP 或管理员 ID 字符串）
     * @return 窗口内未超过 maxRequests 时为 true
     */
    public boolean tryAcquire(String key) {
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> Bucket.builder()
                .withCustomTimePrecision(new ClockTimeMeter(clock))
                .addLimit(Bandwidth.classic(maxRequests, Refill.intervally(maxRequests, window)))
                .build());
        return bucket.tryConsume(1);
    }
}
