package com.susumonitor.server.common.limit;

import io.github.bucket4j.TimeMeter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 将项目统一 {@link Clock} 适配为 Bucket4j 时间源，使令牌补充/窗口推进
 * 在单元测试中可通过可推进时钟确定性验证（与手写限流器的 Clock 注入契约一致）。
 */
public final class ClockTimeMeter implements TimeMeter {

    private final Clock clock;

    /** 注入统一时钟。 */
    public ClockTimeMeter(Clock clock) {
        this.clock = clock;
    }

    /** 以自 Unix 纪元的纳秒数供 Bucket4j 计算令牌补充与窗口推进。 */
    @Override
    public long currentTimeNanos() {
        return Duration.between(Instant.EPOCH, clock.instant()).toNanos();
    }

    /** 基于墙钟语义（窗口按绝对时间推进，而非单调时钟）。 */
    @Override
    public boolean isWallClockBased() {
        return true;
    }
}
