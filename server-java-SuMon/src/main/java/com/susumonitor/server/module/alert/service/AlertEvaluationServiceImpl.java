package com.susumonitor.server.module.alert.service;

import com.susumonitor.server.module.alert.entity.AlertRecordEntity;
import com.susumonitor.server.module.alert.entity.AlertRuleEntity;
import com.susumonitor.server.module.alert.entity.AlertStateEntity;
import com.susumonitor.server.module.alert.enums.AlertRecordStatus;
import com.susumonitor.server.module.alert.mapper.AlertRecordMapper;
import com.susumonitor.server.module.alert.mapper.AlertRuleMapper;
import com.susumonitor.server.module.alert.mapper.AlertStateMapper;
import com.susumonitor.server.module.alert.outbox.AlertTriggeredEnvelopeFactory;
import com.susumonitor.server.module.alert.vo.AlertRecordVo;
import com.susumonitor.server.module.metrics.outbox.OutboxService;
import com.susumonitor.server.module.metrics.vo.MetricsLatestVo;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 告警评估器：对一次指标快照评估全部匹配规则并维护状态和记录。
 *
 * <p>MVP-11 起由消息消费者（{@code AlertMessageConsumer}）在消费事务内调用，
 * 评估结果与消费幂等记录同事务提交；失败由消费者重试，不在此处吞异常。</p>
 *
 * <p>状态迁移通过 AlertStateMachine 纯逻辑判断，数据库操作通过 Mapper
 * 执行。乐观锁冲突时记录 warn 日志并跳过本轮，不重试。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertEvaluationServiceImpl implements AlertEvaluationService {

    private final AlertRuleMapper ruleMapper;
    private final AlertStateMapper stateMapper;
    private final AlertRecordMapper recordMapper;
    private final AlertStateMachine stateMachine;
    private final ApplicationEventPublisher eventPublisher;
    private final OutboxService outboxService;
    private final AlertTriggeredEnvelopeFactory envelopeFactory;
    private final Clock clock;

    /**
     * 对指标快照执行全部匹配规则的评估。
     *
     * <p>事务边界由调用方（消息消费者）管理，本方法以 REQUIRED 加入调用方事务，
     * 保证评估写入与消费幂等记录同事务提交或回滚。</p>
     */
    @Override
    @Transactional
    public void evaluate(MetricsLatestVo metrics) {
        List<AlertRuleEntity> rules = ruleMapper.selectEnabledRulesForServer(metrics.getServerId());
        if (rules.isEmpty()) {
            return;
        }
        for (AlertRuleEntity rule : rules) {
            try {
                evaluateRule(rule, metrics);
            } catch (Exception exception) {
                log.warn("alert evaluation failed, ruleId={}, serverId={}",
                        rule.getId(), metrics.getServerId(), exception);
            }
        }
    }

    /**
     * 评估单条规则，根据状态机决策执行对应操作。
     */
    private void evaluateRule(AlertRuleEntity rule, MetricsLatestVo metrics) {
        AlertStateEntity state = stateMapper.selectByRuleAndServer(rule.getId(), metrics.getServerId());
        AlertTransition transition = stateMachine.evaluate(rule, metrics, state);

        switch (transition) {
            case AlertTransition.Trigger t -> handleTrigger(rule, metrics.getServerId(), t.currentValue(), state);
            case AlertTransition.ContinueBreached c -> handleContinue(c.state(), c.currentValue());
            case AlertTransition.Resolve r -> handleResolve(r.state());
            case AlertTransition.CountingStart s -> handleCountingStart(rule, metrics.getServerId());
            case AlertTransition.CountingProgress p -> handleCountingProgress(p.state());
            case AlertTransition.CountingReset r -> handleCountingReset(r.state());
            case AlertTransition.NoAction ignored -> {
            }
        }
    }

    /**
     * 触发：创建 unread record + active state，发布 AlertTriggeredEvent。
     * state 非空表示逃逸窗口计数行达到阈值后升级触发（active=false → active=true 并绑定 record）。
     */
    private void handleTrigger(AlertRuleEntity rule, Long serverId, BigDecimal currentValue,
            AlertStateEntity state) {
        LocalDateTime now = LocalDateTime.now(clock);
        // 创建告警记录。
        AlertRecordEntity record = new AlertRecordEntity();
        record.setRuleId(rule.getId());
        record.setServerId(serverId);
        record.setMetric(rule.getMetric());
        record.setCurrentValue(currentValue);
        record.setThresholdValue(rule.getThresholdValue());
        record.setLevel(rule.getLevel());
        record.setStatus(AlertRecordStatus.UNREAD.ruleValue());
        record.setMessage(buildMessage(rule.getMetric(), rule.getOperator(), currentValue, rule.getThresholdValue()));
        record.setTriggeredAt(now);
        recordMapper.insertRecord(record);

        if (state == null) {
            // 普通首次越界：创建全新活跃状态行。
            AlertStateEntity newState = new AlertStateEntity();
            newState.setRuleId(rule.getId());
            newState.setServerId(serverId);
            newState.setActive(true);
            newState.setBreachCount(0);
            newState.setAlertRecordId(record.getId());
            newState.setFirstTriggeredAt(now);
            newState.setLastTriggeredAt(now);
            newState.setVersion(0);
            stateMapper.insertState(newState);
        } else {
            // 逃逸窗口计数行达到 confirm_count，升级为活跃并绑定 record。
            int updated = stateMapper.activateOnBreachThreshold(state.getId(), record.getId(), now, state.getVersion());
            if (updated == 0) {
                log.warn("alert state optimistic lock conflict during activation, stateId={}, version={}",
                        state.getId(), state.getVersion());
            }
        }

        // 发布告警触发事件供 WS 推送与外部通知（AFTER_COMMIT 生效）。
        AlertRecordVo recordVo = toVo(record);
        eventPublisher.publishEvent(new AlertTriggeredEvent(serverId, recordVo));

        // 与告警记录同事务登记 Outbox 待发布事件（alert.triggered.v1 契约落地）：
        // eventId 由本处生成并写入信封，保证行内 event_id 与 payload 中 event_id 一致；
        // 事务回滚时 outbox 行一并回滚，保证"已入库告警记录必有待发布事件"。
        String eventId = UUID.randomUUID().toString();
        String envelope = envelopeFactory.build(recordVo, eventId);
        outboxService.enqueue(AlertTriggeredEnvelopeFactory.EVENT_TYPE,
                AlertTriggeredEnvelopeFactory.ROUTING_KEY, envelope, eventId);
    }

    /** 逃逸窗口计数开始：创建 active=false 计数状态行（连续越界计数为 1）。 */
    private void handleCountingStart(AlertRuleEntity rule, Long serverId) {
        LocalDateTime now = LocalDateTime.now(clock);
        AlertStateEntity state = new AlertStateEntity();
        state.setRuleId(rule.getId());
        state.setServerId(serverId);
        state.setActive(false);
        state.setBreachCount(1);
        state.setFirstTriggeredAt(now);
        state.setLastTriggeredAt(now);
        state.setVersion(0);
        stateMapper.insertState(state);
    }

    /** 逃逸窗口计数递增：连续越界 count+1（未达阈值不触发）。 */
    private void handleCountingProgress(AlertStateEntity state) {
        int updated = stateMapper.incrementBreachCount(state.getId(), LocalDateTime.now(clock), state.getVersion());
        if (updated == 0) {
            log.warn("alert state optimistic lock conflict during counting, stateId={}, version={}",
                    state.getId(), state.getVersion());
        }
    }

    /** 逃逸窗口计数重置：连续越界中断，删除计数状态行。 */
    private void handleCountingReset(AlertStateEntity state) {
        int deleted = stateMapper.deleteState(state.getId(), state.getVersion());
        if (deleted == 0) {
            log.warn("alert state optimistic lock conflict during counting reset, stateId={}, version={}",
                    state.getId(), state.getVersion());
        }
    }

    /**
     * 持续越界：更新 last_triggered_at 和 version，不创建新 record。
     */
    private void handleContinue(AlertStateEntity state, BigDecimal currentValue) {
        int updated = stateMapper.updateStateActive(
                state.getId(), state.getAlertRecordId(),
                LocalDateTime.now(clock), state.getVersion());
        if (updated == 0) {
            log.warn("alert state optimistic lock conflict, stateId={}, version={}",
                    state.getId(), state.getVersion());
        }
    }

    /**
     * 恢复：标记 record resolved + 删除 state 行（恢复后下次评估 state 为 null，可再次触发）。
     */
    private void handleResolve(AlertStateEntity state) {
        LocalDateTime now = LocalDateTime.now(clock);
        recordMapper.updateStatusToResolved(state.getAlertRecordId(), now);
        int deleted = stateMapper.deleteState(state.getId(), state.getVersion());
        if (deleted == 0) {
            log.warn("alert state optimistic lock conflict during resolve, stateId={}, version={}",
                    state.getId(), state.getVersion());
        }
    }

    /** 构建告警消息文本。 */
    private String buildMessage(String metric, String operator, BigDecimal currentValue, BigDecimal threshold) {
        return metric + " " + operator + " " + threshold + " (current: " + currentValue + ")";
    }

    /** 将 Entity 转换为 VO，时间字段转为 UTC OffsetDateTime。 */
    private AlertRecordVo toVo(AlertRecordEntity entity) {
        AlertRecordVo vo = new AlertRecordVo();
        vo.setId(entity.getId());
        vo.setRuleId(entity.getRuleId());
        vo.setServerId(entity.getServerId());
        vo.setMetric(entity.getMetric());
        vo.setCurrentValue(entity.getCurrentValue());
        vo.setThresholdValue(entity.getThresholdValue());
        vo.setLevel(entity.getLevel());
        vo.setStatus(entity.getStatus());
        vo.setMessage(entity.getMessage());
        vo.setTriggeredAt(AlertRecordVo.toOffset(entity.getTriggeredAt()));
        vo.setNotifiedAt(AlertRecordVo.toOffset(entity.getNotifiedAt()));
        vo.setNotifyChannels(entity.getNotifyChannels());
        vo.setCreatedAt(AlertRecordVo.toOffset(entity.getCreatedAt()));
        return vo;
    }
}
