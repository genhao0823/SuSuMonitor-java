package com.susumonitor.server.module.command.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 观察期评审报告响应体（对外 snake_case，契约见 openapi-command.json 0.2.0）。
 *
 * <p>承载 M2 自动审批出口评审所需的窗口聚合数据与基线核对结论；
 * criteria 的中文标签由前端按 key 映射，后端只输出稳定契约字段。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CommandObservationReportVo {

    @JsonProperty("window_days")
    private Integer windowDays;
    @JsonProperty("window_start")
    private OffsetDateTime windowStart;
    @JsonProperty("window_end")
    private OffsetDateTime windowEnd;

    @JsonProperty("total_runs")
    private Long totalRuns;

    /** 按状态计数（仅含非零项）。 */
    @JsonProperty("by_status")
    private Map<String, Long> byStatus;
    /** 按审批方式计数（仅含非零项）。 */
    @JsonProperty("by_approval_mode")
    private Map<String, Long> byApprovalMode;
    /** 按风险等级计数（仅含非零项）。 */
    @JsonProperty("by_risk_level")
    private Map<String, Long> byRiskLevel;
    /** 按来源计数（仅含非零项）。 */
    @JsonProperty("by_source")
    private Map<String, Long> bySource;

    @JsonProperty("auto_executed")
    private Long autoExecuted;
    @JsonProperty("auto_failed")
    private Long autoFailed;
    @JsonProperty("manual_executed")
    private Long manualExecuted;
    @JsonProperty("manual_failed")
    private Long manualFailed;

    @JsonProperty("distinct_servers")
    private Long distinctServers;
    @JsonProperty("avg_duration_ms")
    private BigDecimal avgDurationMs;
    @JsonProperty("max_duration_ms")
    private Long maxDurationMs;

    @JsonProperty("template_usage")
    private List<TemplateUsage> templateUsage;

    private Policy policy;

    private List<Criterion> criteria;

    /** 总体结论：pass / fail / insufficient（样本不足）。 */
    private String overall;

    /** 单条基线核对项；passed 为 null 表示样本不足、无法评判（三态）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Criterion {

        /** 稳定契约 key，前端映射中文标签。 */
        private String key;

        /** 计数项为整数、比率项为 0-1 之间保留 4 位小数；无数据时为 null。 */
        private Number value;

        /** 基线表达式的人类可读描述，如 "&lt;= 0.05 且 auto 样本 &gt;= 10"。 */
        private String threshold;

        /** true/false 判定；null = 样本不足。 */
        private Boolean passed;

        public String getKey() { return key; }
        public void setKey(String value) { key = value; }

        public Number getValue() { return value; }
        public void setValue(Number value) { this.value = value; }

        public String getThreshold() { return threshold; }
        public void setThreshold(String value) { threshold = value; }

        public Boolean getPassed() { return passed; }
        public void setPassed(Boolean value) { passed = value; }
    }

    /** 模板用量条目（窗口内 Top N）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TemplateUsage {

        @JsonProperty("template_id")
        private String templateId;

        private Long runs;

        public String getTemplateId() { return templateId; }
        public void setTemplateId(String value) { templateId = value; }

        public Long getRuns() { return runs; }
        public void setRuns(Long value) { runs = value; }
    }

    /** 当前自动审批策略快照（单行策略表，窗口内变更不追踪）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Policy {

        private Boolean enabled;

        @JsonProperty("max_risk_level")
        private String maxRiskLevel;

        @JsonProperty("updated_at")
        private OffsetDateTime updatedAt;

        @JsonProperty("updated_by")
        private Long updatedBy;

        /** v1 已知边界说明：策略无历史快照，窗口内变更未追踪。 */
        private String note;

        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean value) { enabled = value; }

        public String getMaxRiskLevel() { return maxRiskLevel; }
        public void setMaxRiskLevel(String value) { maxRiskLevel = value; }

        public OffsetDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(OffsetDateTime value) { updatedAt = value; }

        public Long getUpdatedBy() { return updatedBy; }
        public void setUpdatedBy(Long value) { updatedBy = value; }

        public String getNote() { return note; }
        public void setNote(String value) { note = value; }
    }

    public Integer getWindowDays() { return windowDays; }
    public void setWindowDays(Integer value) { windowDays = value; }

    public OffsetDateTime getWindowStart() { return windowStart; }
    public void setWindowStart(OffsetDateTime value) { windowStart = value; }

    public OffsetDateTime getWindowEnd() { return windowEnd; }
    public void setWindowEnd(OffsetDateTime value) { windowEnd = value; }

    public Long getTotalRuns() { return totalRuns; }
    public void setTotalRuns(Long value) { totalRuns = value; }

    public Map<String, Long> getByStatus() { return byStatus; }
    public void setByStatus(Map<String, Long> value) { byStatus = value; }

    public Map<String, Long> getByApprovalMode() { return byApprovalMode; }
    public void setByApprovalMode(Map<String, Long> value) { byApprovalMode = value; }

    public Map<String, Long> getByRiskLevel() { return byRiskLevel; }
    public void setByRiskLevel(Map<String, Long> value) { byRiskLevel = value; }

    public Map<String, Long> getBySource() { return bySource; }
    public void setBySource(Map<String, Long> value) { bySource = value; }

    public Long getAutoExecuted() { return autoExecuted; }
    public void setAutoExecuted(Long value) { autoExecuted = value; }

    public Long getAutoFailed() { return autoFailed; }
    public void setAutoFailed(Long value) { autoFailed = value; }

    public Long getManualExecuted() { return manualExecuted; }
    public void setManualExecuted(Long value) { manualExecuted = value; }

    public Long getManualFailed() { return manualFailed; }
    public void setManualFailed(Long value) { manualFailed = value; }

    public Long getDistinctServers() { return distinctServers; }
    public void setDistinctServers(Long value) { distinctServers = value; }

    public BigDecimal getAvgDurationMs() { return avgDurationMs; }
    public void setAvgDurationMs(BigDecimal value) { avgDurationMs = value; }

    public Long getMaxDurationMs() { return maxDurationMs; }
    public void setMaxDurationMs(Long value) { maxDurationMs = value; }

    public List<TemplateUsage> getTemplateUsage() { return templateUsage; }
    public void setTemplateUsage(List<TemplateUsage> value) { templateUsage = value; }

    public Policy getPolicy() { return policy; }
    public void setPolicy(Policy value) { policy = value; }

    public List<Criterion> getCriteria() { return criteria; }
    public void setCriteria(List<Criterion> value) { criteria = value; }

    public String getOverall() { return overall; }
    public void setOverall(String value) { overall = value; }
}
