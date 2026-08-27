package com.susumonitor.server.module.alert.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.susumonitor.server.module.alert.entity.AlertRuleEntity;
import com.susumonitor.server.module.alert.entity.AlertStateEntity;
import com.susumonitor.server.module.metrics.vo.MetricsLatestVo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * 验证告警状态机的首次越界、持续越界、恢复和边界场景。
 */
class AlertStateMachineTests {

    private final AlertStateMachine stateMachine = new AlertStateMachine();

    /** 首次越界（无活跃状态）应返回 Trigger。 */
    @Test
    void firstBreachShouldReturnTrigger() {
        AlertRuleEntity rule = rule("cpu", ">", bd("80"));
        MetricsLatestVo metrics = metrics(bd("90"), null, null, null, null);

        AlertTransition transition = stateMachine.evaluate(rule, metrics, null);

        assertInstanceOf(AlertTransition.Trigger.class, transition);
        assertEquals(bd("90"), ((AlertTransition.Trigger) transition).currentValue());
    }

    /** 持续越界（已有活跃状态）应返回 ContinueBreached，不创建新 record。 */
    @Test
    void continuedBreachShouldReturnContinueBreached() {
        AlertRuleEntity rule = rule("cpu", ">", bd("80"));
        MetricsLatestVo metrics = metrics(bd("95"), null, null, null, null);
        AlertStateEntity state = activeState();

        AlertTransition transition = stateMachine.evaluate(rule, metrics, state);

        assertInstanceOf(AlertTransition.ContinueBreached.class, transition);
        assertEquals(bd("95"), ((AlertTransition.ContinueBreached) transition).currentValue());
    }

    /** 恢复（活跃状态 + 不再越界）应返回 Resolve。 */
    @Test
    void recoveryShouldReturnResolve() {
        AlertRuleEntity rule = rule("cpu", ">", bd("80"));
        MetricsLatestVo metrics = metrics(bd("50"), null, null, null, null);
        AlertStateEntity state = activeState();

        AlertTransition transition = stateMachine.evaluate(rule, metrics, state);

        assertInstanceOf(AlertTransition.Resolve.class, transition);
    }

    /** 恢复后再次越界（state 为 null，因为已 resolved 的 state 被清除）应返回 Trigger。 */
    @Test
    void breachAfterRecoveryShouldReturnTrigger() {
        AlertRuleEntity rule = rule("cpu", ">", bd("80"));
        MetricsLatestVo metrics = metrics(bd("90"), null, null, null, null);

        // state 为 null 表示恢复后已被清除。
        AlertTransition transition = stateMachine.evaluate(rule, metrics, null);

        assertInstanceOf(AlertTransition.Trigger.class, transition);
    }

    /** 不越界且无活跃状态应返回 NoAction。 */
    @Test
    void noBreachNoStateShouldReturnNoAction() {
        AlertRuleEntity rule = rule("cpu", ">", bd("80"));
        MetricsLatestVo metrics = metrics(bd("50"), null, null, null, null);

        AlertTransition transition = stateMachine.evaluate(rule, metrics, null);

        assertInstanceOf(AlertTransition.NoAction.class, transition);
    }

    /** 不越界但有已恢复状态（active=false）应返回 NoAction。 */
    @Test
    void noBreachWithResolvedStateShouldReturnNoAction() {
        AlertRuleEntity rule = rule("cpu", ">", bd("80"));
        MetricsLatestVo metrics = metrics(bd("50"), null, null, null, null);
        AlertStateEntity state = resolvedState();

        AlertTransition transition = stateMachine.evaluate(rule, metrics, state);

        // 实际语义：active=false 是逃逸窗口计数状态行，不再越界 → 计数重置删除该行，清理后无残留活动状态。
        // 恢复后的正式状态行在 handleResolve 中已被删除，因此该场景返回 CountingReset。
        assertInstanceOf(AlertTransition.CountingReset.class, transition);
    }

    /** 操作符 > 不含等于，等于阈值应返回 NoAction。 */
    @Test
    void greaterThanShouldNotIncludeEqual() {
        AlertRuleEntity rule = rule("cpu", ">", bd("80"));
        MetricsLatestVo metrics = metrics(bd("80"), null, null, null, null);

        AlertTransition transition = stateMachine.evaluate(rule, metrics, null);

        assertInstanceOf(AlertTransition.NoAction.class, transition);
    }

    /** 操作符 >= 含等于，等于阈值应返回 Trigger。 */
    @Test
    void greaterThanOrEqualShouldIncludeEqual() {
        AlertRuleEntity rule = rule("cpu", ">=", bd("80"));
        MetricsLatestVo metrics = metrics(bd("80"), null, null, null, null);

        AlertTransition transition = stateMachine.evaluate(rule, metrics, null);

        assertInstanceOf(AlertTransition.Trigger.class, transition);
    }

    /** 操作符 < 在低于阈值时应返回 Trigger。 */
    @Test
    void lessThanShouldTriggerWhenBelow() {
        AlertRuleEntity rule = rule("memory", "<", bd("10"));
        MetricsLatestVo metrics = metrics(null, bd("5"), null, null, null);

        AlertTransition transition = stateMachine.evaluate(rule, metrics, null);

        assertInstanceOf(AlertTransition.Trigger.class, transition);
    }

    /** 操作符 <= 在等于阈值时应返回 Trigger。 */
    @Test
    void lessThanOrEqualShouldIncludeEqual() {
        AlertRuleEntity rule = rule("disk", "<=", bd("100"));
        MetricsLatestVo metrics = metrics(null, null, bd("100"), null, null);

        AlertTransition transition = stateMachine.evaluate(rule, metrics, null);

        assertInstanceOf(AlertTransition.Trigger.class, transition);
    }

    /** temperature 指标为 null（Windows 无温度传感器）应返回 NoAction。 */
    @Test
    void nullMetricValueShouldReturnNoAction() {
        AlertRuleEntity rule = rule("temperature", ">", bd("80"));
        MetricsLatestVo metrics = metrics(null, null, null, null, null);

        AlertTransition transition = stateMachine.evaluate(rule, metrics, null);

        assertInstanceOf(AlertTransition.NoAction.class, transition);
    }

    /** load 指标越界应返回 Trigger。 */
    @Test
    void loadMetricShouldTrigger() {
        AlertRuleEntity rule = rule("load", ">", bd("2.0"));
        MetricsLatestVo metrics = metrics(null, null, null, null, bd("3.5"));

        AlertTransition transition = stateMachine.evaluate(rule, metrics, null);

        assertInstanceOf(AlertTransition.Trigger.class, transition);
    }

    /** 非法 metric 字符串应返回 NoAction。 */
    @Test
    void invalidMetricShouldReturnNoAction() {
        AlertRuleEntity rule = rule("network", ">", bd("80"));
        MetricsLatestVo metrics = metrics(bd("90"), null, null, null, null);

        AlertTransition transition = stateMachine.evaluate(rule, metrics, null);

        assertInstanceOf(AlertTransition.NoAction.class, transition);
    }

    /** 非法 operator 字符串应返回 NoAction。 */
    @Test
    void invalidOperatorShouldReturnNoAction() {
        AlertRuleEntity rule = rule("cpu", "==", bd("80"));
        MetricsLatestVo metrics = metrics(bd("90"), null, null, null, null);

        AlertTransition transition = stateMachine.evaluate(rule, metrics, null);

        assertInstanceOf(AlertTransition.NoAction.class, transition);
    }

    /** 持续越界后恢复，再越界：完整状态周期。 */
    @Test
    void fullCycleShouldWork() {
        AlertRuleEntity rule = rule("cpu", ">", bd("80"));
        AlertStateMachine sm = new AlertStateMachine();

        // 1. 首次越界 → Trigger
        AlertTransition t1 = sm.evaluate(rule, metrics(bd("90"), null, null, null, null), null);
        assertInstanceOf(AlertTransition.Trigger.class, t1);

        // 2. 持续越界 → ContinueBreached
        AlertStateEntity activeState = activeState();
        AlertTransition t2 = sm.evaluate(rule, metrics(bd("95"), null, null, null, null), activeState);
        assertInstanceOf(AlertTransition.ContinueBreached.class, t2);

        // 3. 恢复 → Resolve
        AlertTransition t3 = sm.evaluate(rule, metrics(bd("50"), null, null, null, null), activeState);
        assertInstanceOf(AlertTransition.Resolve.class, t3);

        // 4. 恢复后再次越界（state 被清除为 null）→ Trigger
        AlertTransition t4 = sm.evaluate(rule, metrics(bd("90"), null, null, null, null), null);
        assertInstanceOf(AlertTransition.Trigger.class, t4);
    }

    // --- 辅助方法 ---

    private AlertRuleEntity rule(String metric, String operator, BigDecimal threshold) {
        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setId(1L);
        rule.setMetric(metric);
        rule.setOperator(operator);
        rule.setThresholdValue(threshold);
        rule.setLevel("warning");
        rule.setConfirmCount(1);
        rule.setEnabled(true);
        rule.setDeleted(false);
        return rule;
    }

    private MetricsLatestVo metrics(BigDecimal cpu, BigDecimal memory, BigDecimal disk,
            BigDecimal temperature, BigDecimal load) {
        MetricsLatestVo vo = new MetricsLatestVo();
        vo.setServerId(1L);
        vo.setCpuPercent(cpu);
        vo.setMemoryPercent(memory);
        vo.setDiskPercent(disk);
        vo.setTemperature(temperature);
        vo.setLoadAvg(load);
        return vo;
    }

    private AlertStateEntity activeState() {
        AlertStateEntity state = new AlertStateEntity();
        state.setId(1L);
        state.setRuleId(1L);
        state.setServerId(1L);
        state.setActive(true);
        state.setAlertRecordId(10L);
        state.setFirstTriggeredAt(LocalDateTime.now());
        state.setLastTriggeredAt(LocalDateTime.now());
        state.setVersion(0);
        return state;
    }

    private AlertStateEntity resolvedState() {
        AlertStateEntity state = new AlertStateEntity();
        state.setId(1L);
        state.setRuleId(1L);
        state.setServerId(1L);
        state.setActive(false);
        state.setVersion(1);
        return state;
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    // ---- 逃逸窗口（confirm_count > 1）测试 ----

    /** 首次越界且 confirm_count>1 应返回 CountingStart 而非 Trigger。 */
    @Test
    void firstBreachWithConfirmWindowShouldReturnCountingStart() {
        AlertRuleEntity rule = rule("cpu", ">", bd("80"));
        rule.setConfirmCount(3);

        AlertTransition transition = stateMachine.evaluate(rule, metrics(bd("90"), null, null, null, null), null);

        assertInstanceOf(AlertTransition.CountingStart.class, transition);
    }

    /** 计数状态行续越界但未达 confirm_count 应返回 CountingProgress。 */
    @Test
    void countingStateProgressShouldReturnCountingProgress() {
        AlertRuleEntity rule = rule("cpu", ">", bd("80"));
        rule.setConfirmCount(3);
        AlertStateEntity countingState = new AlertStateEntity();
        countingState.setActive(false);
        countingState.setBreachCount(1);
        countingState.setVersion(0);

        AlertTransition transition = stateMachine.evaluate(rule, metrics(bd("90"), null, null, null, null),
                countingState);

        assertInstanceOf(AlertTransition.CountingProgress.class, transition);
    }

    /** 计数值已达 confirm_count-1，本次越界达到阈值 → Trigger。 */
    @Test
    void countingReachingThresholdShouldReturnTrigger() {
        AlertRuleEntity rule = rule("cpu", ">", bd("80"));
        rule.setConfirmCount(3);
        AlertStateEntity countingState = new AlertStateEntity();
        countingState.setActive(false);
        countingState.setBreachCount(2); // 已累计 2 次，本次为第 3 次达到 confirm_count。
        countingState.setVersion(0);

        AlertTransition transition = stateMachine.evaluate(rule, metrics(bd("90"), null, null, null, null),
                countingState);

        assertInstanceOf(AlertTransition.Trigger.class, transition);
    }

    /** 计数状态行恢复（不再越界）应返回 CountingReset 清除计数。 */
    @Test
    void countingStateRecoveryShouldReturnCountingReset() {
        AlertRuleEntity rule = rule("cpu", ">", bd("80"));
        rule.setConfirmCount(3);
        AlertStateEntity countingState = new AlertStateEntity();
        countingState.setActive(false);
        countingState.setBreachCount(2);
        countingState.setVersion(0);

        AlertTransition transition = stateMachine.evaluate(rule, metrics(bd("50"), null, null, null, null),
                countingState);

        assertInstanceOf(AlertTransition.CountingReset.class, transition);
    }
}
