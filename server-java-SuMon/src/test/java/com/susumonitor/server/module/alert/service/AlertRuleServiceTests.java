package com.susumonitor.server.module.alert.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.module.alert.dto.CreateAlertRuleRequest;
import com.susumonitor.server.module.alert.dto.UpdateAlertRuleRequest;
import com.susumonitor.server.module.alert.entity.AlertRuleEntity;
import com.susumonitor.server.module.alert.mapper.AlertRuleMapper;
import com.susumonitor.server.module.alert.vo.AlertRuleVo;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * 验证告警规则 CRUD 服务。
 */
@ExtendWith(MockitoExtension.class)
class AlertRuleServiceTests {

    private static final Instant FIXED_TIME = Instant.parse("2026-07-22T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(FIXED_TIME, ZoneOffset.UTC);

    @Mock
    private AlertRuleMapper ruleMapper;
    @InjectMocks
    private AlertRuleServiceImpl service;

    /** admin 创建合法规则应成功。 */
    @Test
    void createRuleShouldSucceed() {
        service = new AlertRuleServiceImpl(ruleMapper, CLOCK);
        CreateAlertRuleRequest request = new CreateAlertRuleRequest();
        request.setServerId(1L);
        request.setMetric("cpu");
        request.setOperator(">");
        request.setThresholdValue(new BigDecimal("80"));
        request.setLevel("warning");

        AlertRuleEntity entity = ruleEntity(1L, "cpu", ">", "80", "warning");
        when(ruleMapper.selectActiveRuleById(any())).thenReturn(entity);

        AlertRuleVo vo = service.createRule(request, 10L);

        verify(ruleMapper).insertRule(any());
        assertEquals(1L, vo.getId());
        assertEquals("cpu", vo.getMetric());
        assertEquals("warning", vo.getLevel());
    }

    /** 已存在同签名活跃规则时创建应返回 40900。 */
    @Test
    void createDuplicateRuleShouldReturnConflict() {
        service = new AlertRuleServiceImpl(ruleMapper, CLOCK);
        CreateAlertRuleRequest request = createRequest("80", "warning");
        when(ruleMapper.existsActiveRule(1L, "cpu", ">", new BigDecimal("80"), "warning", null))
                .thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.createRule(request, 10L));

        assertEquals(ErrorCode.RESOURCE_CONFLICT, exception.getErrorCode());
        verify(ruleMapper, org.mockito.Mockito.never()).insertRule(any());
    }

    /** 并发场景下数据库唯一约束触发时创建应返回 40900。 */
    @Test
    void createDuplicateKeyShouldReturnConflict() {
        service = new AlertRuleServiceImpl(ruleMapper, CLOCK);
        CreateAlertRuleRequest request = createRequest("80", "warning");
        doThrow(new DuplicateKeyException("duplicate")).when(ruleMapper).insertRule(any());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.createRule(request, 10L));

        assertEquals(ErrorCode.RESOURCE_CONFLICT, exception.getErrorCode());
    }

    /** 更新为其他活跃规则的同签名时应返回 40900。 */
    @Test
    void updateToDuplicateRuleShouldReturnConflict() {
        service = new AlertRuleServiceImpl(ruleMapper, CLOCK);
        when(ruleMapper.selectActiveRuleById(1L)).thenReturn(ruleEntity(1L, "cpu", ">", "80", "warning"));
        UpdateAlertRuleRequest request = updateRequest("90", "critical", true);
        when(ruleMapper.existsActiveRule(1L, "cpu", ">", new BigDecimal("90"), "critical", 1L))
                .thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.updateRule(1L, request));

        assertEquals(ErrorCode.RESOURCE_CONFLICT, exception.getErrorCode());
        verify(ruleMapper, org.mockito.Mockito.never()).updateRule(anyLong(), any(), any(), any(), any(), any(), any(), any());
    }

    /** 并发场景下数据库唯一约束触发时更新应返回 40900。 */
    @Test
    void updateDuplicateKeyShouldReturnConflict() {
        service = new AlertRuleServiceImpl(ruleMapper, CLOCK);
        when(ruleMapper.selectActiveRuleById(1L)).thenReturn(ruleEntity(1L, "cpu", ">", "80", "warning"));
        UpdateAlertRuleRequest request = updateRequest("90", "critical", true);
        doThrow(new DuplicateKeyException("duplicate")).when(ruleMapper)
                .updateRule(1L, new BigDecimal("90"), "critical", true, null, null, null, null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.updateRule(1L, request));

        assertEquals(ErrorCode.RESOURCE_CONFLICT, exception.getErrorCode());
    }

    /** 非法 metric 应返回 40002。 */
    @Test
    void invalidMetricShouldReturnInvalidParameter() {
        service = new AlertRuleServiceImpl(ruleMapper, CLOCK);
        CreateAlertRuleRequest request = new CreateAlertRuleRequest();
        request.setMetric("network");
        request.setOperator(">");
        request.setThresholdValue(new BigDecimal("80"));
        request.setLevel("warning");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.createRule(request, 10L));
        assertEquals(ErrorCode.INVALID_REQUEST_PARAMETER, exception.getErrorCode());
    }

    /** 非法 operator 应返回 40002。 */
    @Test
    void invalidOperatorShouldReturnInvalidParameter() {
        service = new AlertRuleServiceImpl(ruleMapper, CLOCK);
        CreateAlertRuleRequest request = new CreateAlertRuleRequest();
        request.setMetric("cpu");
        request.setOperator("==");
        request.setThresholdValue(new BigDecimal("80"));
        request.setLevel("warning");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.createRule(request, 10L));
        assertEquals(ErrorCode.INVALID_REQUEST_PARAMETER, exception.getErrorCode());
    }

    /** 更新不存在的规则应返回 40400。 */
    @Test
    void updateNonexistentRuleShouldReturnNotFound() {
        service = new AlertRuleServiceImpl(ruleMapper, CLOCK);
        when(ruleMapper.selectActiveRuleById(999L)).thenReturn(null);

        UpdateAlertRuleRequest request = new UpdateAlertRuleRequest();
        request.setThresholdValue(new BigDecimal("90"));
        request.setLevel("critical");
        request.setEnabled(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.updateRule(999L, request));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.getErrorCode());
    }

    /** 软删除不存在的规则应返回 40400。 */
    @Test
    void deleteNonexistentRuleShouldReturnNotFound() {
        service = new AlertRuleServiceImpl(ruleMapper, CLOCK);
        when(ruleMapper.selectActiveRuleById(999L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.deleteRule(999L));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.getErrorCode());
    }

    /** 软删除已存在的规则应调用 softDeleteRule。 */
    @Test
    void deleteRuleShouldSoftDelete() {
        service = new AlertRuleServiceImpl(ruleMapper, CLOCK);
        when(ruleMapper.selectActiveRuleById(1L)).thenReturn(ruleEntity(1L, "cpu", ">", "80", "warning"));

        service.deleteRule(1L);

        verify(ruleMapper).softDeleteRule(eq(1L), any(LocalDateTime.class));
    }

    /** 列表查询应返回所有未删除规则。 */
    @Test
    void listRulesShouldReturnAllActiveRules() {
        service = new AlertRuleServiceImpl(ruleMapper, CLOCK);
        when(ruleMapper.selectActiveRules()).thenReturn(List.of(
                ruleEntity(1L, "cpu", ">", "80", "warning"),
                ruleEntity(2L, "memory", ">=", "90", "critical")));

        var rules = service.listRules();

        assertEquals(2, rules.size());
        assertEquals("cpu", rules.get(0).getMetric());
        assertEquals("memory", rules.get(1).getMetric());
    }

    private CreateAlertRuleRequest createRequest(String threshold, String level) {
        CreateAlertRuleRequest request = new CreateAlertRuleRequest();
        request.setServerId(1L);
        request.setMetric("cpu");
        request.setOperator(">");
        request.setThresholdValue(new BigDecimal(threshold));
        request.setLevel(level);
        return request;
    }

    private UpdateAlertRuleRequest updateRequest(String threshold, String level, boolean enabled) {
        UpdateAlertRuleRequest request = new UpdateAlertRuleRequest();
        request.setThresholdValue(new BigDecimal(threshold));
        request.setLevel(level);
        request.setEnabled(enabled);
        return request;
    }

    private AlertRuleEntity ruleEntity(Long id, String metric, String operator,
            String threshold, String level) {
        AlertRuleEntity entity = new AlertRuleEntity();
        entity.setId(id);
        entity.setServerId(1L);
        entity.setMetric(metric);
        entity.setOperator(operator);
        entity.setThresholdValue(new BigDecimal(threshold));
        entity.setLevel(level);
        entity.setEnabled(true);
        entity.setDeleted(false);
        entity.setCreatedAt(LocalDateTime.now(CLOCK));
        entity.setUpdatedAt(LocalDateTime.now(CLOCK));
        return entity;
    }
}
