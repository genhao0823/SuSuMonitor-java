package com.susumonitor.server.module.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.common.vo.PageResult;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.ai.provider.AiProvider;
import com.susumonitor.server.module.ai.provider.AiProviderResolver;
import com.susumonitor.server.module.ai.provider.CommandSuggestion;
import com.susumonitor.server.module.alert.service.AlertRecordService;
import com.susumonitor.server.module.command.entity.CommandRunEntity;
import com.susumonitor.server.module.metrics.service.MetricsService;
import com.susumonitor.server.module.server.service.ServerService;
import com.susumonitor.server.module.server.vo.ServerStatusVo;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** 验证 AI 建议与自动审批策略的编排：按策略触发/跳过、单条失败隔离。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiCommandSuggestionServiceTests {

    private static final Long ACTOR = 2L;
    private static final Long SERVER_ID = 8L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-03T00:00:00Z"), ZoneOffset.UTC);

    @Mock private AiProviderResolver providerResolver;
    @Mock private CommandRunService commandRunService;
    @Mock private CommandAutoApprovalPolicyService autoApprovalPolicy;
    @Mock private ServerService serverService;
    @Mock private MetricsService metricsService;
    @Mock private AlertRecordService alertRecordService;
    @Mock private AiProvider aiProvider;

    private AiCommandSuggestionService service;
    private CommandTemplateRegistry registry;

    @BeforeEach
    void setUp() {
        AppProperties appProperties = new AppProperties();
        appProperties.getAi().setEnabled(true);
        registry = new CommandTemplateRegistry();
        service = new AiCommandSuggestionService(providerResolver, commandRunService, registry,
                autoApprovalPolicy, serverService, metricsService, alertRecordService,
                appProperties, CLOCK);
        when(providerResolver.resolveForActor(ACTOR)).thenReturn(new AiProviderResolver.Resolution(
                aiProvider, "openai-compatible", "test-model", AiProviderResolver.Source.GLOBAL));
        when(serverService.existsActive(SERVER_ID)).thenReturn(true);
        ServerStatusVo statusVo = mock(ServerStatusVo.class);
        when(statusVo.getStatus()).thenReturn("online");
        when(statusVo.getAgentStatus()).thenReturn("online");
        when(serverService.status(SERVER_ID)).thenReturn(statusVo);
        com.susumonitor.server.common.vo.PageResult<com.susumonitor.server.module.alert.vo.AlertRecordVo>
                emptyPage = new com.susumonitor.server.common.vo.PageResult<>();
        emptyPage.setItems(List.of());
        when(alertRecordService.listRecords(eq(SERVER_ID), any(), eq(1), eq(10)))
                .thenReturn(emptyPage);
        when(aiProvider.suggestCommands(anyString(), any(), any()))
                .thenReturn(List.of(new CommandSuggestion("uptime", Map.of(), "看看负载")));
    }

    /** 策略允许时创建后立即自动审批，返回下发后的最新状态。 */
    @Test
    void shouldAutoApproveWhenPolicyAllows() {
        when(autoApprovalPolicy.allows(registry.find("uptime"))).thenReturn(true);
        when(commandRunService.createPendingRun(anyLong(), anyLong(), anyString(), anyMap(),
                anyString(), any())).thenReturn(run(101L, "pending_approval"));
        when(commandRunService.autoApprove(101L)).thenReturn(run(101L, "executing"));

        List<CommandRunEntity> created = service.suggestAndCreate(ACTOR, SERVER_ID, "看一下系统负载");

        assertEquals(1, created.size());
        assertEquals("executing", created.get(0).getStatus());
        verify(commandRunService).autoApprove(101L);
    }

    /** 策略禁用（默认）时不触发自动审批，保持 pending_approval。 */
    @Test
    void shouldKeepPendingWhenPolicyDisabled() {
        when(autoApprovalPolicy.allows(any())).thenReturn(false);
        when(commandRunService.createPendingRun(anyLong(), anyLong(), anyString(), anyMap(),
                anyString(), any())).thenReturn(run(101L, "pending_approval"));

        List<CommandRunEntity> created = service.suggestAndCreate(ACTOR, SERVER_ID, "看一下系统负载");

        assertEquals(1, created.size());
        assertEquals("pending_approval", created.get(0).getStatus());
        verify(commandRunService, never()).autoApprove(anyLong());
    }

    /** 单条自动审批失败（Agent 离线）不阻断：返回失败后的最新行，其余建议照常。 */
    @Test
    void autoApprovalFailureShouldNotBreakOtherSuggestions() {
        when(autoApprovalPolicy.allows(any())).thenReturn(true);
        when(aiProvider.suggestCommands(anyString(), any(), any())).thenReturn(List.of(
                new CommandSuggestion("uptime", Map.of(), "a"),
                new CommandSuggestion("disk_free", Map.of(), "b")));
        when(commandRunService.createPendingRun(anyLong(), anyLong(), eq("uptime"), anyMap(),
                anyString(), any())).thenReturn(run(101L, "pending_approval"));
        when(commandRunService.createPendingRun(anyLong(), anyLong(), eq("disk_free"), anyMap(),
                anyString(), any())).thenReturn(run(102L, "pending_approval"));
        when(commandRunService.autoApprove(101L))
                .thenThrow(new BusinessException(ErrorCode.COMMAND_AGENT_OFFLINE));
        when(commandRunService.get(101L)).thenReturn(run(101L, "failed"));
        when(commandRunService.autoApprove(102L)).thenReturn(run(102L, "executing"));

        List<CommandRunEntity> created = service.suggestAndCreate(ACTOR, SERVER_ID, "看下负载和磁盘");

        assertEquals(2, created.size());
        assertEquals("failed", created.get(0).getStatus());
        assertEquals("executing", created.get(1).getStatus());
        verify(commandRunService).get(101L);
    }

    private CommandRunEntity run(Long id, String status) {
        CommandRunEntity entity = new CommandRunEntity();
        entity.setId(id);
        entity.setExecutionId("exec-" + id);
        entity.setProposerId(ACTOR);
        entity.setServerId(SERVER_ID);
        entity.setTemplateId("uptime");
        entity.setParamsJson("{}");
        entity.setRenderedCommand("uptime");
        entity.setStatus(status);
        entity.setSource(CommandRunService.SOURCE_AI);
        entity.setRiskLevel("low");
        entity.setApprovalMode(CommandRunService.APPROVAL_MODE_MANUAL);
        return entity;
    }
}
