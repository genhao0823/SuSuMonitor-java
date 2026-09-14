package com.susumonitor.server.module.command.dto;

import java.math.BigDecimal;

/**
 * 观察期评审报告的窗口聚合行（MyBatis 别名直映射，非实体）。
 *
 * <p>由 {@code CommandRunMapper.selectObservationAggregate} 对
 * {@code ai_command_runs} 在 created_at 窗口上做单条条件聚合得到；
 * 所有计数字段使用包装类型，便于服务层区分"无该状态行"（0）与聚合行为。</p>
 */
public class CommandObservationAggregate {

    /** 窗口内总运行数（COUNT(*)，恒非空）。 */
    private Long totalRuns;

    // ---- 按状态计数（SUM(CASE...)，无行时 MySQL 返回 NULL，统一按 0 处理）----
    private Long statusPendingApproval;
    private Long statusApproved;
    private Long statusExecuting;
    private Long statusSucceeded;
    private Long statusFailed;
    private Long statusRejected;
    private Long statusExpired;
    private Long statusTimeout;

    // ---- 按审批方式 / 风险等级 / 来源计数 ----
    private Long approvalModeAuto;
    private Long approvalModeManual;
    private Long riskLevelLow;
    private Long riskLevelMedium;
    private Long riskLevelHigh;
    private Long sourceAi;
    private Long sourceManual;

    // ---- 执行质量组合计数 ----
    /** 自动审批且风险等级为 high 的行数（策略不变量守护：正常业务下恒为 0）。 */
    private Long highRiskAuto;
    /** 自动审批且到达已执行终态（succeeded/failed/timeout）的行数。 */
    private Long autoExecuted;
    /** 自动审批且终态为 failed/timeout 的行数。 */
    private Long autoFailed;
    /** 人工审批且到达已执行终态的行数。 */
    private Long manualExecuted;
    /** 人工审批且终态为 failed/timeout 的行数。 */
    private Long manualFailed;

    /** 窗口内出现过命令的目标服务器去重数。 */
    private Long distinctServers;
    /** 已执行终态行的平均耗时（毫秒；AVG 忽略 NULL，无终态行时为 null）。 */
    private BigDecimal avgDurationMs;
    /** 已执行终态行的最大耗时（毫秒；无终态行时为 null）。 */
    private Long maxDurationMs;

    public Long getTotalRuns() { return totalRuns; }
    public void setTotalRuns(Long value) { totalRuns = value; }

    public Long getStatusPendingApproval() { return statusPendingApproval; }
    public void setStatusPendingApproval(Long value) { statusPendingApproval = value; }

    public Long getStatusApproved() { return statusApproved; }
    public void setStatusApproved(Long value) { statusApproved = value; }

    public Long getStatusExecuting() { return statusExecuting; }
    public void setStatusExecuting(Long value) { statusExecuting = value; }

    public Long getStatusSucceeded() { return statusSucceeded; }
    public void setStatusSucceeded(Long value) { statusSucceeded = value; }

    public Long getStatusFailed() { return statusFailed; }
    public void setStatusFailed(Long value) { statusFailed = value; }

    public Long getStatusRejected() { return statusRejected; }
    public void setStatusRejected(Long value) { statusRejected = value; }

    public Long getStatusExpired() { return statusExpired; }
    public void setStatusExpired(Long value) { statusExpired = value; }

    public Long getStatusTimeout() { return statusTimeout; }
    public void setStatusTimeout(Long value) { statusTimeout = value; }

    public Long getApprovalModeAuto() { return approvalModeAuto; }
    public void setApprovalModeAuto(Long value) { approvalModeAuto = value; }

    public Long getApprovalModeManual() { return approvalModeManual; }
    public void setApprovalModeManual(Long value) { approvalModeManual = value; }

    public Long getRiskLevelLow() { return riskLevelLow; }
    public void setRiskLevelLow(Long value) { riskLevelLow = value; }

    public Long getRiskLevelMedium() { return riskLevelMedium; }
    public void setRiskLevelMedium(Long value) { riskLevelMedium = value; }

    public Long getRiskLevelHigh() { return riskLevelHigh; }
    public void setRiskLevelHigh(Long value) { riskLevelHigh = value; }

    public Long getSourceAi() { return sourceAi; }
    public void setSourceAi(Long value) { sourceAi = value; }

    public Long getSourceManual() { return sourceManual; }
    public void setSourceManual(Long value) { sourceManual = value; }

    public Long getHighRiskAuto() { return highRiskAuto; }
    public void setHighRiskAuto(Long value) { highRiskAuto = value; }

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
}
