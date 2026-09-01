package com.susumonitor.server.module.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.susumonitor.server.module.ai.vo.AiEvidenceVo;
import java.util.List;

/** 发送给模型的最小白名单上下文，不包含服务器地址、凭据、通知或终端数据。 */
public record AiDiagnosisContext(
        @JsonProperty("server_id") Long serverId,
        @JsonProperty("server_status") String serverStatus,
        @JsonProperty("agent_status") String agentStatus,
        @JsonProperty("history_minutes") int historyMinutes,
        List<AiEvidenceVo> evidence,
        @JsonProperty("alert_summaries") List<AlertSummary> alertSummaries) {

    /** 只包含状态和值的告警摘要，刻意排除自由文本 message 和通知信息。 */
    public record AlertSummary(Long id, String metric, String status, String level,
            @JsonProperty("current_value") Object currentValue,
            @JsonProperty("threshold_value") Object thresholdValue,
            @JsonProperty("triggered_at") String triggeredAt) {
    }
}
