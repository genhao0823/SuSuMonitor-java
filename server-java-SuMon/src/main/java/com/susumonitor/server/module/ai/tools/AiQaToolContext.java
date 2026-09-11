package com.susumonitor.server.module.ai.tools;

import com.susumonitor.server.module.ai.vo.AiToolCallVo;
import java.util.ArrayList;
import java.util.List;

/**
 * 单次问答的工具调用上下文：按请求绑定发起管理员、工具轮次预算与调用审计。
 *
 * <p>Spring AI 的工具回调在发起线程内同步执行，因此用 ThreadLocal 绑定即可。
 * {@code open} 与 {@code close} 必须成对出现（由 AiQaService 在 finally 中保证）；
 * 未 open 时工具一律拒绝执行（防御性，正常链路不会发生）。</p>
 */
public final class AiQaToolContext {

    private static final ThreadLocal<AiQaToolContext> CURRENT = new ThreadLocal<>();

    /** 单次问答最多记录的工具调用条数（与轮次上限解耦的审计硬上限）。 */
    private static final int MAX_RECORDED_CALLS = 50;

    private final Long actorId;
    private final int maxToolCalls;
    private int usedToolCalls;
    private final List<AiToolCallVo> calls = new ArrayList<>();

    private AiQaToolContext(Long actorId, int maxToolCalls) {
        this.actorId = actorId;
        this.maxToolCalls = maxToolCalls;
    }

    /** 为当前线程绑定一次问答的工具上下文。 */
    public static void open(Long actorId, int maxToolCalls) {
        CURRENT.set(new AiQaToolContext(actorId, maxToolCalls));
    }

    /** 解除当前线程的工具上下文绑定。 */
    public static void close() {
        CURRENT.remove();
    }

    /** 获取当前线程的工具上下文；未 open 返回 null。 */
    public static AiQaToolContext current() {
        return CURRENT.get();
    }

    /** 计数一次工具调用；超过本次问答的轮次预算时抛出异常终止工具循环。 */
    void checkIteration() {
        if (++usedToolCalls > maxToolCalls) {
            throw new IllegalStateException("AI QA tool call budget exhausted: " + maxToolCalls);
        }
    }

    /** 记录一次工具调用概要（超出审计硬上限后静默丢弃，不影响执行）。 */
    void record(String tool, String args) {
        if (calls.size() < MAX_RECORDED_CALLS) {
            calls.add(new AiToolCallVo(tool, args));
        }
    }

    /** 本此问答发起管理员的用户 ID（供命令提案工具绑定 proposer，模型无法伪造）。 */
    public Long actorId() {
        return actorId;
    }

    /** 已记录的工具调用快照。 */
    public List<AiToolCallVo> calls() {
        return List.copyOf(calls);
    }
}
