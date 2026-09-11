package com.susumonitor.server.module.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.module.command.entity.CommandAutoApprovalPolicyEntity;
import com.susumonitor.server.module.command.mapper.CommandAutoApprovalPolicyMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** 验证自动审批策略：fail-closed 默认、阈值比较与更新校验。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommandAutoApprovalPolicyServiceTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-03T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private CommandAutoApprovalPolicyMapper policyMapper;

    private CommandAutoApprovalPolicyService service;
    private CommandTemplateRegistry registry;

    @BeforeEach
    void setUp() {
        service = new CommandAutoApprovalPolicyService(policyMapper, CLOCK);
        registry = new CommandTemplateRegistry();
    }

    /** 策略行缺失：按禁用返回（fail-closed），allows 全部拒绝。 */
    @Test
    void missingRowShouldReadAsDisabled() {
        when(policyMapper.selectPolicy(anyInt())).thenReturn(null);

        CommandAutoApprovalPolicyService.Snapshot snapshot = service.get();

        assertFalse(snapshot.enabled());
        assertFalse(snapshot.present());
        assertFalse(service.allows(registry.find("disk_free")));
    }

    /** 读库异常同样按禁用处理，不影响命令域其余功能。 */
    @Test
    void readFailureShouldFallbackToDisabled() {
        when(policyMapper.selectPolicy(anyInt())).thenThrow(new RuntimeException("db down"));

        assertFalse(service.get().enabled());
        assertFalse(service.allows(registry.find("uptime")));
    }

    /** 阈值 medium：low/medium 模板放行（medium 当前无注册模板，逻辑预留）。 */
    @Test
    void enabledPolicyShouldAllowWithinThreshold() {
        stubPolicy(true, "medium");

        assertTrue(service.get().enabled());
        assertTrue(service.allows(registry.find("disk_free")));
    }

    /** 阈值 low：仅 low 放行；high 模板在任何配置下都拒绝。 */
    @Test
    void lowThresholdShouldOnlyAllowLowTemplates() {
        stubPolicy(true, "low");

        assertTrue(service.allows(registry.find("disk_free")));
        CommandTemplateRegistry.Template high = new CommandTemplateRegistry.Template(
                "future_write", new String[]{"echo", "hi"}, new CommandTemplateRegistry.ParamSpec[0],
                CommandRiskLevel.HIGH);
        assertFalse(service.allows(high));
    }

    /** 开关关闭时无论阈值如何一律拒绝。 */
    @Test
    void disabledPolicyShouldRejectEverything() {
        stubPolicy(false, "medium");

        assertFalse(service.allows(registry.find("disk_free")));
    }

    /** 更新：合法值 upsert 并返回最新快照。 */
    @Test
    void updateShouldUpsertAndReturnSnapshot() {
        when(policyMapper.upsertPolicy(eq(true), eq("low"), eq(2L), any())).thenReturn(1);
        stubPolicy(true, "low");

        CommandAutoApprovalPolicyService.Snapshot snapshot = service.update(true, "low", 2L);

        assertTrue(snapshot.enabled());
        assertEquals(CommandRiskLevel.LOW, snapshot.maxRiskLevel());
        verify(policyMapper).upsertPolicy(eq(true), eq("low"), eq(2L),
                eq(LocalDateTime.parse("2026-09-03T00:00:00")));
    }

    /** 更新校验：enabled 缺失、阈值非法或 high 一律 40002。 */
    @Test
    void updateShouldRejectInvalidInput() {
        assertThrows(BusinessException.class, () -> service.update(null, "low", 2L));
        BusinessException invalid = assertThrows(BusinessException.class,
                () -> service.update(true, "urgent", 2L));
        assertEquals(ErrorCode.INVALID_REQUEST_PARAMETER, invalid.getErrorCode());
        BusinessException high = assertThrows(BusinessException.class,
                () -> service.update(true, "high", 2L));
        assertEquals(ErrorCode.INVALID_REQUEST_PARAMETER, high.getErrorCode());
        verify(policyMapper, org.mockito.Mockito.never()).upsertPolicy(any(), any(), anyLong(), any());
    }

    private void stubPolicy(boolean enabled, String maxRiskLevel) {
        CommandAutoApprovalPolicyEntity entity = new CommandAutoApprovalPolicyEntity();
        entity.setId(1);
        entity.setEnabled(enabled);
        entity.setMaxRiskLevel(maxRiskLevel);
        entity.setUpdatedBy(2L);
        entity.setUpdatedAt(LocalDateTime.parse("2026-09-03T00:00:00"));
        when(policyMapper.selectPolicy(anyInt())).thenReturn(entity);
    }
}
