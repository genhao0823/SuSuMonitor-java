package com.susumonitor.server.module.ai.provider;

import com.susumonitor.server.module.ai.vo.AiUsageVo;

/**
 * 健康报告的单次 LLM 摘要输出：一页式总结 + "值得关注的三件事" + limitations。
 * 事实数据一律以服务端聚合的 {@code AiHealthReportFacts} 为准，模型输出仅作解读。
 */
public record AiHealthReportSummary(
        String summary,
        @com.fasterxml.jackson.annotation.JsonProperty("top_concerns") java.util.List<String> topConcerns,
        java.util.List<String> limitations,
        AiUsageVo usage) {
}
