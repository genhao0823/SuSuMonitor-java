package com.susumonitor.server.module.ai.provider;

import com.susumonitor.server.module.ai.vo.AiUsageVo;

/**
 * 一次问答调用的模型原始产出：自由文本回答与用量摘要。
 *
 * @param answer 模型回答文本（已通过长度与内容防线校验）
 * @param usage 本次调用的 token 用量
 */
public record AiQaAnswer(String answer, AiUsageVo usage) {
}
