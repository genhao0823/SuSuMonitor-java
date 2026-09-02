package com.susumonitor.server.module.ai.limit;

/**
 * 定义 AI 只读诊断的按管理员限流契约。
 *
 * <p>实现按 {@code actorId} 固定窗口计数，超限直接抛出
 * {@code BusinessException(ErrorCode.AI_RATE_LIMIT_REACHED)}（42906）。
 * 语义为"检查即计数"：每次通过调用即消耗一次窗口配额。</p>
 */
public interface AiDiagnosisRateLimiter {

    /**
     * 记录并校验一次管理员诊断尝试。
     *
     * @param actorId 发起诊断的管理员用户 ID
     */
    void checkAllowed(Long actorId);
}
