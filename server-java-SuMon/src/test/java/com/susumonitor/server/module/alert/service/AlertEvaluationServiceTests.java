package com.susumonitor.server.module.alert.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.susumonitor.server.module.alert.entity.AlertRecordEntity;
import com.susumonitor.server.module.alert.entity.AlertRuleEntity;
import com.susumonitor.server.module.alert.entity.AlertStateEntity;
import com.susumonitor.server.module.alert.mapper.AlertRecordMapper;
import com.susumonitor.server.module.alert.mapper.AlertRuleMapper;
import com.susumonitor.server.module.alert.mapper.AlertStateMapper;
import com.susumonitor.server.module.metrics.vo.MetricsLatestVo;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 验证告警评估器的首次越界、持续越界、恢复和并发场景。
 */
@ExtendWith(MockitoExtension.class)
class AlertEvaluationServiceTests {

    private static final Instant FIXED_TIME = Instant.parse("2026-07-22T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(FIXED_TIME, ZoneOffset.UTC);

    @Mock
    private AlertRuleMapper ruleMapper;
    @Mock
    private AlertStateMapper stateMapper;
    @Mock
    private AlertRecordMapper recordMapper;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    private final AlertStateMachine stateMachine = new AlertStateMachine();

    private AlertEvaluationService service;

    /** 首次越界应创建 record + state 并发布事件。 */
    @Test
    void firstBreachShouldInsertRecordStateAndPublishEvent() {
        setupService();
        AlertRuleEntity rule = rule(1L, "cpu", ">", bd("80"));
        MetricsLatestVo metrics = metrics(bd("90"));
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of(rule));
        when(stateMapper.selectByRuleAndServer(1L, 1L)).thenReturn(null);

        service.evaluate(metrics);

        verify(recordMapper).insertRecord(any(AlertRecordEntity.class));
        verify(stateMapper).insertState(any(AlertStateEntity.class));
        verify(eventPublisher).publishEvent(any(AlertTriggeredEvent.class));
    }

    /** 持续越界应更新 state 但不创建新 record 也不发布事件。 */
    @Test
    void continuedBreachShouldUpdateStateWithoutNewRecordOrEvent() {
        setupService();
        AlertRuleEntity rule = rule(1L, "cpu", ">", bd("80"));
        MetricsLatestVo metrics = metrics(bd("95"));
        AlertStateEntity state = activeState(1L, 1L, 1L);
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of(rule));
        when(stateMapper.selectByRuleAndServer(1L, 1L)).thenReturn(state);
        when(stateMapper.updateStateActive(anyLong(), anyLong(), any(LocalDateTime.class), eq(0))).thenReturn(1);

        service.evaluate(metrics);

        verify(stateMapper).updateStateActive(eq(1L), eq(1L), any(LocalDateTime.class), eq(0));
        verify(recordMapper, never()).insertRecord(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    /** 恢复应标记 record resolved 并删除 state 行（恢复后 state 为 null 才能再次触发）。 */
    @Test
    void recoveryShouldMarkResolved() {
        setupService();
        AlertRuleEntity rule = rule(1L, "cpu", ">", bd("80"));
        MetricsLatestVo metrics = metrics(bd("50"));
        AlertStateEntity state = activeState(1L, 1L, 1L);
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of(rule));
        when(stateMapper.selectByRuleAndServer(1L, 1L)).thenReturn(state);
        when(stateMapper.deleteState(eq(1L), eq(0))).thenReturn(1);

        service.evaluate(metrics);

        verify(recordMapper).updateStatusToResolved(eq(1L), any(LocalDateTime.class));
        verify(stateMapper).deleteState(eq(1L), eq(0));
        verify(eventPublisher, never()).publishEvent(any());
    }

    /** 恢复后再次越界（state 为 null）应创建新 record + state。 */
    @Test
    void breachAfterRecoveryShouldCreateNewRecord() {
        setupService();
        AlertRuleEntity rule = rule(1L, "cpu", ">", bd("80"));
        MetricsLatestVo metrics = metrics(bd("90"));
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of(rule));
        when(stateMapper.selectByRuleAndServer(1L, 1L)).thenReturn(null);

        service.evaluate(metrics);

        verify(recordMapper).insertRecord(any(AlertRecordEntity.class));
        verify(stateMapper).insertState(any(AlertStateEntity.class));
        verify(eventPublisher).publishEvent(any(AlertTriggeredEvent.class));
    }

    /** 无启用规则时不执行任何操作。 */
    @Test
    void noRulesShouldDoNothing() {
        setupService();
        MetricsLatestVo metrics = metrics(bd("90"));
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of());

        service.evaluate(metrics);

        verify(stateMapper, never()).selectByRuleAndServer(anyLong(), anyLong());
        verify(recordMapper, never()).insertRecord(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    /** 多规则同时命中应各自独立创建 record。 */
    @Test
    void multipleRulesShouldEachCreateRecord() {
        setupService();
        AlertRuleEntity rule1 = rule(1L, "cpu", ">", bd("80"));
        AlertRuleEntity rule2 = rule(2L, "memory", ">=", bd("90"));
        MetricsLatestVo metrics = metrics(bd("90"), bd("95"));
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of(rule1, rule2));
        when(stateMapper.selectByRuleAndServer(eq(1L), eq(1L))).thenReturn(null);
        when(stateMapper.selectByRuleAndServer(eq(2L), eq(1L))).thenReturn(null);

        service.evaluate(metrics);

        verify(recordMapper, times(2)).insertRecord(any(AlertRecordEntity.class));
        verify(stateMapper, times(2)).insertState(any(AlertStateEntity.class));
        verify(eventPublisher, times(2)).publishEvent(any(AlertTriggeredEvent.class));
    }

    /** 乐观锁冲突应跳过不抛异常。 */
    @Test
    void optimisticLockConflictShouldNotThrow() {
        setupService();
        AlertRuleEntity rule = rule(1L, "cpu", ">", bd("80"));
        MetricsLatestVo metrics = metrics(bd("95"));
        AlertStateEntity state = activeState(1L, 1L, 1L);
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of(rule));
        when(stateMapper.selectByRuleAndServer(1L, 1L)).thenReturn(state);
        when(stateMapper.updateStateActive(anyLong(), anyLong(), any(LocalDateTime.class), eq(0))).thenReturn(0);

        service.evaluate(metrics);

        verify(stateMapper).updateStateActive(eq(1L), eq(1L), any(LocalDateTime.class), eq(0));
        verify(recordMapper, never()).insertRecord(any());
    }

    /** 评估失败应记录日志但不影响其他规则。 */
    @Test
    void evaluationFailureShouldNotAffectOtherRules() {
        setupService();
        AlertRuleEntity rule1 = rule(1L, "cpu", ">", bd("80"));
        AlertRuleEntity rule2 = rule(2L, "memory", ">=", bd("90"));
        MetricsLatestVo metrics = metrics(bd("90"), bd("95"));
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of(rule1, rule2));
        // rule1 的 stateMapper 抛异常，模拟 DB 错误。
        when(stateMapper.selectByRuleAndServer(1L, 1L)).thenThrow(new RuntimeException("DB error"));
        when(stateMapper.selectByRuleAndServer(2L, 1L)).thenReturn(null);

        service.evaluate(metrics);

        // rule2 仍应正常评估。
        verify(recordMapper, times(1)).insertRecord(any(AlertRecordEntity.class));
    }

    // --- 辅助方法 ---

    private void setupService() {
        service = new AlertEvaluationServiceImpl(ruleMapper, stateMapper, recordMapper,
                stateMachine, eventPublisher, CLOCK);
    }

    // ---- 逃逸窗口（confirm_count > 1）----

    /** 首次越界确认数>1：创建计数行（active=false）不触发，不建 record 不发事件。 */
    @Test
    void firstBreachWithConfirmWindowCreatesCountingRowOnly() {
        setupService();
        AlertRuleEntity rule = rule(1L, "cpu", ">", bd("80"));
        rule.setConfirmCount(3);
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of(rule));
        when(stateMapper.selectByRuleAndServer(1L, 1L)).thenReturn(null);

        service.evaluate(metrics(bd("90")));

        verify(stateMapper).insertState(argThat(state ->
                !Boolean.TRUE.equals(state.getActive()) && state.getBreachCount() == 1));
        verify(recordMapper, never()).insertRecord(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    /** 计数未达阈值：仅递增 breach_count。 */
    @Test
    void countingBreachProgressIncrementsCountOnly() {
        setupService();
        AlertRuleEntity rule = rule(1L, "cpu", ">", bd("80"));
        rule.setConfirmCount(3);
        AlertStateEntity counting = new AlertStateEntity();
        counting.setId(1L);
        counting.setActive(false);
        counting.setBreachCount(1);
        counting.setVersion(0);
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of(rule));
        when(stateMapper.selectByRuleAndServer(1L, 1L)).thenReturn(counting);
        when(stateMapper.incrementBreachCount(eq(1L), any(LocalDateTime.class), eq(0))).thenReturn(1);

        service.evaluate(metrics(bd("90")));

        verify(stateMapper).incrementBreachCount(eq(1L), any(LocalDateTime.class), eq(0));
        verify(recordMapper, never()).insertRecord(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    /** 计数达阈值：升级活跃 + 建 record + 发事件（激活用 activateOnBreachThreshold）。 */
    @Test
    void countingReachingThresholdActivatesAndRecords() {
        setupService();
        AlertRuleEntity rule = rule(1L, "cpu", ">", bd("80"));
        rule.setConfirmCount(3);
        AlertStateEntity counting = new AlertStateEntity();
        counting.setId(1L);
        counting.setActive(false);
        counting.setBreachCount(2);
        counting.setVersion(0);
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of(rule));
        when(stateMapper.selectByRuleAndServer(1L, 1L)).thenReturn(counting);
        when(stateMapper.activateOnBreachThreshold(eq(1L), any(), any(LocalDateTime.class), eq(0)))
                .thenReturn(1);

        service.evaluate(metrics(bd("90")));

        verify(recordMapper).insertRecord(any(AlertRecordEntity.class));
        verify(stateMapper).activateOnBreachThreshold(eq(1L), any(), any(LocalDateTime.class), eq(0));
        verify(eventPublisher).publishEvent(any(AlertTriggeredEvent.class));
    }

    /** 计数中断恢复：删除计数行。 */
    @Test
    void countingRecoveryDeletesCountingRow() {
        setupService();
        AlertRuleEntity rule = rule(1L, "cpu", ">", bd("80"));
        rule.setConfirmCount(3);
        AlertStateEntity counting = new AlertStateEntity();
        counting.setId(1L);
        counting.setActive(false);
        counting.setBreachCount(2);
        counting.setVersion(0);
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of(rule));
        when(stateMapper.selectByRuleAndServer(1L, 1L)).thenReturn(counting);
        when(stateMapper.deleteState(eq(1L), eq(0))).thenReturn(1);

        service.evaluate(metrics(bd("50")));

        verify(stateMapper).deleteState(eq(1L), eq(0));
        verify(recordMapper, never()).insertRecord(any());
    }

    private AlertRuleEntity rule(Long id, String metric, String operator, BigDecimal threshold) {
        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setId(id);
        rule.setServerId(null);
        rule.setMetric(metric);
        rule.setOperator(operator);
        rule.setThresholdValue(threshold);
        rule.setLevel("warning");
        rule.setEnabled(true);
        rule.setDeleted(false);
        return rule;
    }

    private MetricsLatestVo metrics(BigDecimal cpu) {
        MetricsLatestVo vo = new MetricsLatestVo();
        vo.setServerId(1L);
        vo.setCpuPercent(cpu);
        return vo;
    }

    private MetricsLatestVo metrics(BigDecimal cpu, BigDecimal memory) {
        MetricsLatestVo vo = metrics(cpu);
        vo.setMemoryPercent(memory);
        return vo;
    }

    private AlertStateEntity activeState(Long id, Long ruleId, Long recordId) {
        AlertStateEntity state = new AlertStateEntity();
        state.setId(id);
        state.setRuleId(ruleId);
        state.setServerId(1L);
        state.setActive(true);
        state.setAlertRecordId(recordId);
        state.setFirstTriggeredAt(LocalDateTime.now(CLOCK));
        state.setLastTriggeredAt(LocalDateTime.now(CLOCK));
        state.setVersion(0);
        return state;
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
