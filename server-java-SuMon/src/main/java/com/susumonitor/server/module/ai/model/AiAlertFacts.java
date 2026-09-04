package com.susumonitor.server.module.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 告警触发时刻的冻结事实（来自 ai.alert.explanation.requested.v1 载荷白名单），
 * 供解释 prompt 使用；不含告警展示文本、通知内容或任何凭据。
 */
public record AiAlertFacts(
        @JsonProperty("record_id") Long recordId,
        @JsonProperty("rule_id") Long ruleId,
        @JsonProperty("server_id") Long serverId,
        String metric,
        @JsonProperty("current_value") Object currentValue,
        @JsonProperty("threshold_value") Object thresholdValue,
        String level,
        @JsonProperty("triggered_at") String triggeredAt) {
}
