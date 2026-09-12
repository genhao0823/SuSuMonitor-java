package com.susumonitor.server.module.alert.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.susumonitor.server.module.alert.outbox.AlertResolvedEnvelopeFactory;
import com.susumonitor.server.module.alert.outbox.AlertTriggeredEnvelopeFactory;
import com.susumonitor.server.module.metrics.outbox.OutboxService;
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
import org.springframework.dao.ConcurrencyFailureException;

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
    @Mock
    private OutboxService outboxService;
    @Mock
    private AlertTriggeredEnvelopeFactory envelopeFactory;
    @Mock
    private AlertResolvedEnvelopeFactory resolvedEnvelopeFactory;
    private final AlertStateMachine stateMachine = new AlertStateMachine();

    private AlertEvaluationService service;

    /** 首次越界应创建 record + state 并发布事件与 Outbox 事件登记。 */
    @Test
    void firstBreachShouldInsertRecordStateAndPublishEvent() {
        setupService();
        AlertRuleEntity rule = rule(1L, "cpu", ">", bd("80"));
        MetricsLatestVo metrics = metrics(bd("90"));
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of(rule));
        when(stateMapper.selectByServerId(1L)).thenReturn(List.of());

        service.evaluate(metrics);

        verify(recordMapper).insertRecord(any(AlertRecordEntity.class));
        verify(stateMapper).insertState(any(AlertStateEntity.class));
        verify(eventPublisher).publishEvent(any(AlertTriggeredEvent.class));
        verify(outboxService).enqueue(eq(AlertTriggeredEnvelopeFactory.EVENT_TYPE),
                eq(AlertTriggeredEnvelopeFactory.ROUTING_KEY), any(), any());
    }

    /** 持续越界应更新 state 但不创建新 record 也不发布事件。 */
    @Test
    void continuedBreachShouldUpdateStateWithoutNewRecordOrEvent() {
        setupService();
        AlertRuleEntity rule = rule(1L, "cpu", ">", bd("80"));
        MetricsLatestVo metrics = metrics(bd("95"));
        AlertStateEntity state = activeState(1L, 1L, 1L);
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of(rule));
        when(stateMapper.selectByServerId(1L)).thenReturn(List.of(state));
        when(stateMapper.updateStateActive(anyLong(), anyLong(), any(LocalDateTime.class), eq(0))).thenReturn(1);

        service.evaluate(metrics);

        verify(stateMapper).updateStateActive(eq(1L), eq(1L), any(LocalDateTime.class), eq(0));
        verify(recordMapper, never()).insertRecord(any());
        verify(eventPublisher, never()).publishEvent(any());
        verify(outboxService, never()).enqueue(any(), any(), any(), any());
    }

    /** 恢复应标记 record resolved 并删除 state 行，同时发布恢复事件与 Outbox 登记（与触发对称）。 */
    @Test
    void recoveryShouldMarkResolved() {
        setupService();
        AlertRuleEntity rule = rule(1L, "cpu", ">", bd("80"));
        MetricsLatestVo metrics = metrics(bd("50"));
        AlertStateEntity state = activeState(1L, 1L, 1L);
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of(rule));
        when(stateMapper.selectByServerId(1L)).thenReturn(List.of(state));
        when(recordMapper.updateStatusToResolved(eq(1L), any(LocalDateTime.class))).thenReturn(1);
        when(stateMapper.deleteState(eq(1L), eq(0))).thenReturn(1);
        AlertRecordEntity record = resolvedRecord(1L);
        when(recordMapper.selectRecordById(1L)).thenReturn(record);

        service.evaluate(metrics);

        verify(recordMapper).updateStatusToResolved(eq(1L), any(LocalDateTime.class));
        verify(stateMapper).deleteState(eq(1L), eq(0));
        verify(eventPublisher).publishEvent(any(AlertResolvedEvent.class));
        verify(outboxService).enqueue(eq(AlertResolvedEnvelopeFactory.EVENT_TYPE),
                eq(AlertResolvedEnvelopeFactory.ROUTING_KEY), any(), any());
    }

    /** 记录未实际转为 resolved（已恢复/缺失）时不发恢复事件，避免重复恢复语义。 */
    @Test
    void resolveSkippedWhenRecordAlreadyResolved() {
        setupService();
        AlertRuleEntity rule = rule(1L, "cpu", ">", bd("80"));
        MetricsLatestVo metrics = metrics(bd("50"));
        AlertStateEntity state = activeState(1L, 1L, 1L);
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of(rule));
        when(stateMapper.selectByServerId(1L)).thenReturn(List.of(state));
        when(recordMapper.updateStatusToResolved(eq(1L), any(LocalDateTime.class))).thenReturn(0);

        service.evaluate(metrics);

        verify(stateMapper, never()).deleteState(anyLong(), anyInt());
        verify(eventPublisher, never()).publishEvent(any());
        verify(outboxService, never()).enqueue(any(), any(), any(), any());
    }

    /** 恢复后再次越界（state 为 null）应创建新 record + state。 */
    @Test
    void breachAfterRecoveryShouldCreateNewRecord() {
        setupService();
        AlertRuleEntity rule = rule(1L, "cpu", ">", bd("80"));
        MetricsLatestVo metrics = metrics(bd("90"));
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of(rule));
        when(stateMapper.selectByServerId(1L)).thenReturn(List.of());

        service.evaluate(metrics);

        verify(recordMapper).insertRecord(any(AlertRecordEntity.class));
        verify(stateMapper).insertState(any(AlertStateEntity.class));
        verify(eventPublisher).publishEvent(any(AlertTriggeredEvent.class));
        verify(outboxService).enqueue(eq(AlertTriggeredEnvelopeFactory.EVENT_TYPE),
                eq(AlertTriggeredEnvelopeFactory.ROUTING_KEY), any(), any());
    }

    /** 无启用规则时不执行任何操作。 */
    @Test
    void noRulesShouldDoNothing() {
        setupService();
        MetricsLatestVo metrics = metrics(bd("90"));
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of());

        service.evaluate(metrics);

        verify(stateMapper, never()).selectByServerId(anyLong());
        verify(recordMapper, never()).insertRecord(any());
        verify(eventPublisher, never()).publishEvent(any());
        verify(outboxService, never()).enqueue(any(), any(), any(), any());
    }

    /** 多规则同时命中应各自独立创建 record。 */
    @Test
    void multipleRulesShouldEachCreateRecord() {
        setupService();
        AlertRuleEntity rule1 = rule(1L, "cpu", ">", bd("80"));
        AlertRuleEntity rule2 = rule(2L, "memory", ">=", bd("90"));
        MetricsLatestVo metrics = metrics(bd("90"), bd("95"));
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of(rule1, rule2));
        when(stateMapper.selectByServerId(1L)).thenReturn(List.of());

        service.evaluate(metrics);

        verify(recordMapper, times(2)).insertRecord(any(AlertRecordEntity.class));
        verify(stateMapper, times(2)).insertState(any(AlertStateEntity.class));
        verify(eventPublisher, times(2)).publishEvent(any(AlertTriggeredEvent.class));
        verify(outboxService, times(2)).enqueue(eq(AlertTriggeredEnvelopeFactory.EVENT_TYPE),
                eq(AlertTriggeredEnvelopeFactory.ROUTING_KEY), any(), any());
    }

    /** 持续越界的乐观锁冲突应抛出并发异常（消费事务回滚、容器重试重评），不再吞掉。 */
    @Test
    void optimisticLockConflictShouldPropagate() {
        setupService();
        AlertRuleEntity rule = rule(1L, "cpu", ">", bd("80"));
        MetricsLatestVo metrics = metrics(bd("95"));
        AlertStateEntity state = activeState(1L, 1L, 1L);
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of(rule));
        when(stateMapper.selectByServerId(1L)).thenReturn(List.of(state));
        when(stateMapper.updateStateActive(anyLong(), anyLong(), any(LocalDateTime.class), eq(0))).thenReturn(0);

        assertThrows(ConcurrencyFailureException.class, () -> service.evaluate(metrics));

        verify(stateMapper).updateStateActive(eq(1L), eq(1L), any(LocalDateTime.class), eq(0));
        verify(recordMapper, never()).insertRecord(any());
    }

    /** 触发路径的状态激活冲突：抛异常且不发布事件/不登记 Outbox（防幽灵通知与孤儿记录）。 */
    @Test
    void triggerActivationConflictShouldPropagateWithoutEvents() {
        setupService();
        AlertRuleEntity rule = rule(1L, "cpu", ">", bd("80"));
        MetricsLatestVo metrics = metrics(bd("95"));
        AlertStateEntity countingState = countingState(1L, 1L);
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of(rule));
        when(stateMapper.selectByServerId(1L)).thenReturn(List.of(countingState));
        when(recordMapper.insertRecord(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, AlertRecordEntity.class).setId(501L);
            return 1;
        });
        when(stateMapper.activateOnBreachThreshold(eq(1L), anyLong(), any(LocalDateTime.class), eq(0)))
                .thenReturn(0);

        assertThrows(ConcurrencyFailureException.class, () -> service.evaluate(metrics));

        verify(eventPublisher, never()).publishEvent(any(AlertTriggeredEvent.class));
        verify(outboxService, never()).enqueue(any(), any(), any(), any());
    }

    /** 计数递增冲突：抛并发异常（防止越界计数静默丢失后照常 ACK）。 */
    @Test
    void countingProgressConflictShouldPropagate() {
        setupService();
        AlertRuleEntity rule = rule(1L, "cpu", ">", bd("80"));
        rule.setConfirmCount(3);
        MetricsLatestVo metrics = metrics(bd("95"));
        AlertStateEntity countingState = countingState(1L, 1L);
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of(rule));
        when(stateMapper.selectByServerId(1L)).thenReturn(List.of(countingState));
        when(stateMapper.incrementBreachCount(eq(1L), any(LocalDateTime.class), eq(0))).thenReturn(0);

        assertThrows(ConcurrencyFailureException.class, () -> service.evaluate(metrics));
    }

    /** 恢复路径的状态行删除冲突：抛并发异常（防僵尸状态行导致该规则告警永久静默）。 */
    @Test
    void resolveStateDeleteConflictShouldPropagate() {
        setupService();
        AlertRuleEntity rule = rule(1L, "cpu", ">", bd("80"));
        MetricsLatestVo metrics = metrics(bd("50"));
        AlertStateEntity state = activeState(1L, 1L, 1L);
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of(rule));
        when(stateMapper.selectByServerId(1L)).thenReturn(List.of(state));
        when(recordMapper.updateStatusToResolved(eq(1L), any(LocalDateTime.class))).thenReturn(1);
        when(stateMapper.deleteState(eq(1L), eq(0))).thenReturn(0);

        assertThrows(ConcurrencyFailureException.class, () -> service.evaluate(metrics));

        verify(eventPublisher, never()).publishEvent(any(AlertResolvedEvent.class));
    }

    /** 计数重置冲突：抛并发异常（防止计数行残留使后续触发判定失真）。 */
    @Test
    void countingResetConflictShouldPropagate() {
        setupService();
        AlertRuleEntity rule = rule(1L, "cpu", ">", bd("80"));
        MetricsLatestVo metrics = metrics(bd("50"));
        AlertStateEntity countingState = countingState(1L, 1L);
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of(rule));
        when(stateMapper.selectByServerId(1L)).thenReturn(List.of(countingState));
        when(stateMapper.deleteState(eq(1L), eq(0))).thenReturn(0);

        assertThrows(ConcurrencyFailureException.class, () -> service.evaluate(metrics));
    }

    /** 批量状态查询失败应向上传播（由消息消费者重试整条消息），不再逐规则吞异常。 */
    @Test
    void stateQueryFailureShouldPropagate() {
        setupService();
        AlertRuleEntity rule = rule(1L, "cpu", ">", bd("80"));
        MetricsLatestVo metrics = metrics(bd("90"));
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of(rule));
        when(stateMapper.selectByServerId(1L)).thenThrow(new RuntimeException("DB error"));

        assertThrows(RuntimeException.class, () -> service.evaluate(metrics));

        verify(recordMapper, never()).insertRecord(any());
        verify(stateMapper, never()).insertState(any());
    }

    /** 单规则评估/写入失败应向上传播（由消息消费者回滚并重试整条消息），不再逐规则吞异常。 */
    @Test
    void evaluationFailureShouldPropagate() {
        setupService();
        AlertRuleEntity rule1 = rule(1L, "cpu", ">", bd("80"));
        AlertRuleEntity rule2 = rule(2L, "memory", ">=", bd("90"));
        MetricsLatestVo metrics = metrics(bd("90"), bd("95"));
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of(rule1, rule2));
        when(stateMapper.selectByServerId(1L)).thenReturn(List.of());
        // rule1 的 record 写入抛异常，模拟单规则 DB 错误。
        when(recordMapper.insertRecord(any(AlertRecordEntity.class)))
                .thenThrow(new RuntimeException("DB error"))
                .thenReturn(1);

        assertThrows(RuntimeException.class, () -> service.evaluate(metrics));

        // 异常传播后 rule2 不再被评估；调用方事务回滚、消费幂等记录不写入，等待重试/DLQ。
        verify(recordMapper, times(1)).insertRecord(any(AlertRecordEntity.class));
        verify(stateMapper, never()).insertState(any());
    }

    // --- 辅助方法 ---

    private void setupService() {
        service = new AlertEvaluationServiceImpl(ruleMapper, stateMapper, recordMapper,
                stateMachine, eventPublisher, outboxService, envelopeFactory, resolvedEnvelopeFactory, CLOCK);
    }

    // ---- 逃逸窗口（confirm_count > 1）----

    /** 首次越界确认数>1：创建计数行（active=false）不触发，不建 record 不发事件。 */
    @Test
    void firstBreachWithConfirmWindowCreatesCountingRowOnly() {
        setupService();
        AlertRuleEntity rule = rule(1L, "cpu", ">", bd("80"));
        rule.setConfirmCount(3);
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of(rule));
        when(stateMapper.selectByServerId(1L)).thenReturn(List.of());

        service.evaluate(metrics(bd("90")));

        verify(stateMapper).insertState(argThat(state ->
                !Boolean.TRUE.equals(state.getActive()) && state.getBreachCount() == 1));
        verify(recordMapper, never()).insertRecord(any());
        verify(eventPublisher, never()).publishEvent(any());
        verify(outboxService, never()).enqueue(any(), any(), any(), any());
    }

    /** 计数未达阈值：仅递增 breach_count。 */
    @Test
    void countingBreachProgressIncrementsCountOnly() {
        setupService();
        AlertRuleEntity rule = rule(1L, "cpu", ">", bd("80"));
        rule.setConfirmCount(3);
        AlertStateEntity counting = new AlertStateEntity();
        counting.setId(1L);
        counting.setRuleId(1L);
        counting.setActive(false);
        counting.setBreachCount(1);
        counting.setVersion(0);
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of(rule));
        when(stateMapper.selectByServerId(1L)).thenReturn(List.of(counting));
        when(stateMapper.incrementBreachCount(eq(1L), any(LocalDateTime.class), eq(0))).thenReturn(1);

        service.evaluate(metrics(bd("90")));

        verify(stateMapper).incrementBreachCount(eq(1L), any(LocalDateTime.class), eq(0));
        verify(recordMapper, never()).insertRecord(any());
        verify(eventPublisher, never()).publishEvent(any());
        verify(outboxService, never()).enqueue(any(), any(), any(), any());
    }

    /** 计数达阈值：升级活跃 + 建 record + 发事件（激活用 activateOnBreachThreshold）。 */
    @Test
    void countingReachingThresholdActivatesAndRecords() {
        setupService();
        AlertRuleEntity rule = rule(1L, "cpu", ">", bd("80"));
        rule.setConfirmCount(3);
        AlertStateEntity counting = new AlertStateEntity();
        counting.setId(1L);
        counting.setRuleId(1L);
        counting.setActive(false);
        counting.setBreachCount(2);
        counting.setVersion(0);
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of(rule));
        when(stateMapper.selectByServerId(1L)).thenReturn(List.of(counting));
        when(stateMapper.activateOnBreachThreshold(eq(1L), any(), any(LocalDateTime.class), eq(0)))
                .thenReturn(1);

        service.evaluate(metrics(bd("90")));

        verify(recordMapper).insertRecord(any(AlertRecordEntity.class));
        verify(stateMapper).activateOnBreachThreshold(eq(1L), any(), any(LocalDateTime.class), eq(0));
        verify(eventPublisher).publishEvent(any(AlertTriggeredEvent.class));
        verify(outboxService).enqueue(eq(AlertTriggeredEnvelopeFactory.EVENT_TYPE),
                eq(AlertTriggeredEnvelopeFactory.ROUTING_KEY), any(), any());
    }

    /** 计数中断恢复：删除计数行。 */
    @Test
    void countingRecoveryDeletesCountingRow() {
        setupService();
        AlertRuleEntity rule = rule(1L, "cpu", ">", bd("80"));
        rule.setConfirmCount(3);
        AlertStateEntity counting = new AlertStateEntity();
        counting.setId(1L);
        counting.setRuleId(1L);
        counting.setActive(false);
        counting.setBreachCount(2);
        counting.setVersion(0);
        when(ruleMapper.selectEnabledRulesForServer(1L)).thenReturn(List.of(rule));
        when(stateMapper.selectByServerId(1L)).thenReturn(List.of(counting));
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

    /** 逃逸窗口计数状态行（active=false，已达阈值前）；confirmCount 语义由状态机判定。 */
    private AlertStateEntity countingState(Long id, Long ruleId) {
        AlertStateEntity state = new AlertStateEntity();
        state.setId(id);
        state.setRuleId(ruleId);
        state.setServerId(1L);
        state.setActive(false);
        state.setBreachCount(1);
        state.setAlertRecordId(null);
        state.setFirstTriggeredAt(LocalDateTime.now(CLOCK));
        state.setLastTriggeredAt(LocalDateTime.now(CLOCK));
        state.setVersion(0);
        return state;
    }

    private AlertRecordEntity resolvedRecord(Long id) {
        AlertRecordEntity record = new AlertRecordEntity();
        record.setId(id);
        record.setRuleId(1L);
        record.setServerId(1L);
        record.setMetric("cpu");
        record.setLevel("warning");
        record.setStatus("resolved");
        record.setTriggeredAt(LocalDateTime.now(CLOCK));
        record.setResolvedAt(LocalDateTime.now(CLOCK));
        return record;
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
