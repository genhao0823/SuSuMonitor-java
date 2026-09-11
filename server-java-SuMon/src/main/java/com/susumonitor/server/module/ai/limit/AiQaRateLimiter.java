package com.susumonitor.server.module.ai.limit;

/**
 * 运维问答按管理员固定窗口限流契约；检查即计数，超限抛 42906。
 *
 * <p>与诊断限流（{@link AiDiagnosisRateLimiter}）独立计数：问答与诊断各自窗口，
 * 互不挤占；实现见 {@link AiQaRateLimiterConfig}。</p>
 */
public interface AiQaRateLimiter {

    /**
     * 记录一次问答尝试。
     *
     * @param actorId 发起问答的管理员用户 ID
     */
    void checkAllowed(Long actorId);
}
