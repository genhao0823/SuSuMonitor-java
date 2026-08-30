package com.susumonitor.server.module.alert.service;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.module.alert.dto.CreateAlertRuleRequest;
import com.susumonitor.server.module.alert.dto.UpdateAlertRuleRequest;
import com.susumonitor.server.module.alert.entity.AlertRuleEntity;
import com.susumonitor.server.module.alert.enums.AlertLevel;
import com.susumonitor.server.module.alert.enums.AlertMetric;
import com.susumonitor.server.module.alert.enums.AlertOperator;
import com.susumonitor.server.module.alert.mapper.AlertRuleMapper;
import com.susumonitor.server.module.alert.vo.AlertRuleVo;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 告警规则 CRUD 服务，负责规则创建、查询、更新和软删除。
 *
 * <p>创建时校验 metric/operator/level 字符串是否为合法枚举值。
 * 删除采用软删除，标记 deleted=1，不物理删除。</p>
 */
@Service
@RequiredArgsConstructor
public class AlertRuleServiceImpl implements AlertRuleService {

    private final AlertRuleMapper ruleMapper;
    private final Clock clock;

    /** 创建告警规则，校验 metric/operator/level 合法性。 */
    @Transactional
    public AlertRuleVo createRule(CreateAlertRuleRequest request, Long createdBy) {
        validateCreateRequest(request);
        AlertRuleEntity entity = new AlertRuleEntity();
        entity.setServerId(request.getServerId());
        entity.setMetric(request.getMetric());
        entity.setOperator(request.getOperator());
        entity.setThresholdValue(request.getThresholdValue());
        entity.setLevel(request.getLevel());
        // 逃逸窗口：confirm_count 缺省即为 1（立即触发，向后兼容）。
        entity.setConfirmCount(request.getConfirmCount() == null
                ? 1 : Math.max(1, request.getConfirmCount()));
        entity.setNotifyEmail(request.getNotifyEmail());
        entity.setNotifyDingtalk(request.getNotifyDingtalk());
        entity.setNotifyWebhook(request.getNotifyWebhook());
        entity.setEnabled(true);
        entity.setDeleted(false);
        entity.setCreatedBy(createdBy);
        ensureNoActiveRuleWithSameSignature(entity, null);
        insertRule(entity);
        return toVo(ruleMapper.selectActiveRuleById(entity.getId()), true);
    }

    /** 更新规则阈值、等级和启用状态，不允许修改 metric/operator/serverId。 */
    @Transactional
    public AlertRuleVo updateRule(Long ruleId, UpdateAlertRuleRequest request) {
        AlertRuleEntity entity = ruleMapper.selectActiveRuleById(ruleId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        validateLevel(request.getLevel());
        ensureNoActiveRuleWithSameSignature(entity.getServerId(), entity.getMetric(), entity.getOperator(),
                request.getThresholdValue(), request.getLevel(), ruleId);
        updateRuleWithConflictTranslation(ruleId, request);
        return toVo(ruleMapper.selectActiveRuleById(ruleId), true);
    }

    /** 软删除规则，标记 deleted=1。 */
    @Transactional
    public void deleteRule(Long ruleId) {
        AlertRuleEntity entity = ruleMapper.selectActiveRuleById(ruleId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        ruleMapper.softDeleteRule(ruleId, LocalDateTime.now(clock));
    }

    /** 查询所有未删除规则；非 admin（exposeNotify=false）时脱敏通知渠道详情。 */
    @Transactional(readOnly = true)
    public List<AlertRuleVo> listRules(boolean exposeNotify) {
        return ruleMapper.selectActiveRules().stream()
                .map(entity -> toVo(entity, exposeNotify)).toList();
    }

    /** 校验创建请求中 metric、operator 和 level 的合法性。 */
    private void validateCreateRequest(CreateAlertRuleRequest request) {
        if (AlertMetric.fromValue(request.getMetric()) == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        if (AlertOperator.fromValue(request.getOperator()) == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        validateLevel(request.getLevel());
    }

    /** 校验告警等级字符串是否为合法枚举值。 */
    private void validateLevel(String level) {
        if (AlertLevel.fromValue(level) == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
    }

    /** 检查是否存在同签名的活跃规则（使用 Entity 参数重载）。 */
    private void ensureNoActiveRuleWithSameSignature(AlertRuleEntity rule, Long excludeId) {
        ensureNoActiveRuleWithSameSignature(rule.getServerId(), rule.getMetric(), rule.getOperator(),
                rule.getThresholdValue(), rule.getLevel(), excludeId);
    }

    /** 检查是否存在同签名的活跃规则，存在则抛 RESOURCE_CONFLICT 异常。 */
    private void ensureNoActiveRuleWithSameSignature(Long serverId, String metric, String operator,
            java.math.BigDecimal thresholdValue, String level, Long excludeId) {
        if (ruleMapper.existsActiveRule(serverId, metric, operator, thresholdValue, level, excludeId)) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
        }
    }

    /** 插入规则，捕获唯一键冲突并转换为业务异常。 */
    private void insertRule(AlertRuleEntity entity) {
        try {
            ruleMapper.insertRule(entity);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT, exception);
        }
    }

    /** 更新规则，捕获唯一键冲突并转换为业务异常。 */
    private void updateRuleWithConflictTranslation(Long ruleId, UpdateAlertRuleRequest request) {
        try {
            ruleMapper.updateRule(ruleId, request.getThresholdValue(), request.getLevel(),
                    request.getEnabled(), request.getConfirmCount(),
                    request.getNotifyEmail(), request.getNotifyDingtalk(), request.getNotifyWebhook());
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT, exception);
        }
    }

    /** 将 Entity 转换为 VO，时间字段转为 UTC OffsetDateTime。 */
    private AlertRuleVo toVo(AlertRuleEntity entity, boolean exposeNotify) {
        if (entity == null) {
            return null;
        }
        AlertRuleVo vo = new AlertRuleVo();
        vo.setId(entity.getId());
        vo.setServerId(entity.getServerId());
        vo.setMetric(entity.getMetric());
        vo.setOperator(entity.getOperator());
        vo.setThresholdValue(entity.getThresholdValue());
        vo.setLevel(entity.getLevel());
        vo.setConfirmCount(entity.getConfirmCount());
        // 通知渠道详情（邮件地址、钉钉/Webhook URL）仅 admin 可见；脱敏时保持 null 由 JSON 省略。
        if (exposeNotify) {
            vo.setNotifyEmail(entity.getNotifyEmail());
            vo.setNotifyDingtalk(entity.getNotifyDingtalk());
            vo.setNotifyWebhook(entity.getNotifyWebhook());
        }
        vo.setEnabled(entity.getEnabled());
        vo.setCreatedBy(entity.getCreatedBy());
        vo.setCreatedAt(AlertRuleVo.toOffset(entity.getCreatedAt()));
        vo.setUpdatedAt(AlertRuleVo.toOffset(entity.getUpdatedAt()));
        return vo;
    }
}
