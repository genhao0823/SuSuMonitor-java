package com.susumonitor.server.module.alert.service;

import com.susumonitor.server.module.alert.entity.AlertRuleEntity;
import com.susumonitor.server.module.alert.entity.AlertStateEntity;
import com.susumonitor.server.module.alert.enums.AlertMetric;
import com.susumonitor.server.module.alert.enums.AlertOperator;
import com.susumonitor.server.module.metrics.vo.MetricsLatestVo;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * 告警状态机纯逻辑评估器，不依赖数据库。
 *
 * <p>接收规则、指标快照和当前状态，返回 {@link AlertTransition} 决策。
 * 状态迁移规则：</p>
 * <pre>
 * IDLE     → ACTIVE   首次越界   Trigger
 * ACTIVE   → ACTIVE   持续越界   ContinueBreached
 * ACTIVE   → RESOLVED 恢复       Resolve
 * RESOLVED → IDLE     恢复后重置  （state 被清除后下次评估为 null）
 * </pre>
 *
 * <p>非法的 metric 或 operator 字符串返回 NoAction，不触发告警。</p>
 */
@Component
public class AlertStateMachine {

    /**
     * 评估单条规则与当前指标值，返回状态迁移决策。
     *
     * @param rule       告警规则（metric/operator 为字符串，由本方法转换为枚举）
     * @param metrics    最新指标快照
     * @param currentState 当前状态行，null 表示无活跃状态
     * @return 状态迁移决策
     */
    public AlertTransition evaluate(AlertRuleEntity rule, MetricsLatestVo metrics,
            AlertStateEntity currentState) {
        AlertMetric metric = AlertMetric.fromValue(rule.getMetric());
        AlertOperator operator = AlertOperator.fromValue(rule.getOperator());
        if (metric == null || operator == null) {
            return new AlertTransition.NoAction();
        }

        int confirmCount = Math.max(1,
                rule.getConfirmCount() == null ? 1 : rule.getConfirmCount());
        BigDecimal currentValue = metric.extract(metrics);
        boolean breached = operator.eval(currentValue, rule.getThresholdValue());

        if (breached && currentState == null) {
            // 首次越界：无状态行。
            // confirm_count=1（默认）立即触发；>1 则进入逃逸窗口计数。
            return confirmCount == 1
                    ? new AlertTransition.Trigger(rule, currentValue)
                    : new AlertTransition.CountingStart(rule);
        }
        if (breached && !Boolean.TRUE.equals(currentState.getActive())) {
            // 逃逸窗口计数中：非活跃状态行（active=false），继续越界。
            int breachedSoFar = currentState.getBreachCount() == null ? 0 : currentState.getBreachCount();
            // 已累计 (confirmCount-1) 次，本次为第 confirmCount 次 → 达到阈值触发。
            return breachedSoFar + 1 >= confirmCount
                    ? new AlertTransition.Trigger(rule, currentValue)
                    : new AlertTransition.CountingProgress(currentState);
        }
        if (breached && currentState != null && Boolean.TRUE.equals(currentState.getActive())) {
            // 持续越界：已有活跃状态 → ContinueBreached。
            return new AlertTransition.ContinueBreached(currentState, currentValue);
        }
        if (!breached && currentState != null && Boolean.TRUE.equals(currentState.getActive())) {
            // 恢复：活跃状态 + 不再越界 → Resolve。
            return new AlertTransition.Resolve(currentState);
        }
        if (!breached && currentState != null && !Boolean.TRUE.equals(currentState.getActive())) {
            // 计数状态行 + 恢复：连续越界中断，清除计数行。
            return new AlertTransition.CountingReset(currentState);
        }
        // 不越界且无活跃状态，或状态已恢复（active=false）→ NoAction。
        return new AlertTransition.NoAction();
    }
}
