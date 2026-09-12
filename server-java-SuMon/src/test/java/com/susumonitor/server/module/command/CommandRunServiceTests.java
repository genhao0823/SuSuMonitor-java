package com.susumonitor.server.module.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.command.entity.CommandRunEntity;
import com.susumonitor.server.module.command.mapper.CommandRunMapper;
import com.susumonitor.server.module.metrics.service.MetricsService;
import com.susumonitor.server.module.server.service.ServerService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** 验证命令域 M1 审批状态机：创建/审批/下发/结果回填/过期与超时扫描。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommandRunServiceTests {

    private static final Long ACTOR = 1L;
    private static final Long SERVER_ID = 7L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-03T00:00:00Z"), ZoneOffset.UTC);

    @Mock private CommandRunMapper runMapper;
    @Mock private CommandTransport transport;
    @Mock private ServerService serverService;
    @Mock private MetricsService metricsService;
    @Mock private org.springframework.beans.factory.ObjectProvider<CommandAutoApprovalNotifier> notifierProvider;
    @Mock private CommandAutoApprovalNotifier notifier;

    private AppProperties appProperties;
    private ObjectMapper objectMapper;
    private CommandRunService service;
    private CommandTemplateRegistry registry;

    /** 构造被测服务与默认配置（启用命令域、模板白名单合法、事后通知器可用、同步执行器）。 */
    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getAi().getCommand().setEnabled(true);
        objectMapper = new ObjectMapper();
        registry = new CommandTemplateRegistry();
        org.mockito.Mockito.when(notifierProvider.getIfAvailable()).thenReturn(notifier);
        service = new CommandRunService(runMapper, registry, transport, serverService,
                appProperties, objectMapper, CLOCK, notifierProvider, Runnable::run);
    }

    /** 创建 pending 运行：渲染预览、过期时间、参数 hash 均正确落审计。 */
    @Test
    void createPendingRunShouldRenderAndPersist() {
        when(serverService.existsActive(SERVER_ID)).thenReturn(true);
        when(runMapper.insertRun(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, CommandRunEntity.class).setId(100L);
            return 1;
        });

        CommandRunEntity run = service.createPendingRun(ACTOR, SERVER_ID, "service_status",
                Map.of("unit", "nginx.service"), CommandRunService.SOURCE_MANUAL, null);

        assertEquals("systemctl status nginx.service", run.getRenderedCommand());
        assertEquals(CommandRunService.STATUS_PENDING, run.getStatus());
        assertEquals("low", run.getRiskLevel(), "risk level snapshot from registry");
        assertEquals(CommandRunService.APPROVAL_MODE_MANUAL, run.getApprovalMode());
        assertNotNull(run.getExecutionId());
        assertNotNull(run.getParamsHash());
        assertEquals(LocalDateTime.parse("2026-09-03T00:15"), run.getExpiresAt());
    }

    /** 服务器不存在或模板非法时在持久化前拒绝。 */
    @Test
    void createPendingRunShouldFailClosedOnInvalidInput() {
        when(serverService.existsActive(SERVER_ID)).thenReturn(false);
        assertThrows(BusinessException.class, () -> service.createPendingRun(ACTOR, SERVER_ID,
                "service_status", Map.of("unit", "nginx"), CommandRunService.SOURCE_AI, null));
        when(serverService.existsActive(SERVER_ID)).thenReturn(true);
        assertThrows(BusinessException.class, () -> service.createPendingRun(ACTOR, SERVER_ID,
                "not_a_template", Map.of(), CommandRunService.SOURCE_AI, null));
        verify(runMapper, never()).insertRun(any());
    }

    /** 审批成功后立即下发 command.execute 并进入 executing。 */
    @Test
    void approveShouldDispatchCommandFrame() throws Exception {
        CommandRunEntity pending = pendingRun();
        when(runMapper.selectRunById(100L)).thenReturn(pending);
        when(runMapper.approveRun(eq(100L), eq(ACTOR), any())).thenReturn(1);
        when(transport.send(eq(SERVER_ID), anyString())).thenReturn(true);
        when(runMapper.selectRunById(100L)).thenReturn(pending);

        service.approve(100L, ACTOR);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(transport).send(eq(SERVER_ID), bodyCaptor.capture());
        JsonNode frame = objectMapper.readTree(bodyCaptor.getValue());
        assertEquals("command.execute", frame.path("type").asText());
        assertEquals(SERVER_ID, frame.path("payload").path("server_id").asLong());
        assertEquals(pending.getExecutionId(), frame.path("payload").path("execution_id").asText());
        assertEquals("service_status", frame.path("payload").path("template").asText());
        verify(runMapper).markExecuting(100L);
    }

    /** Agent 离线（下发失败）时运行置 failed(40906) 并向审批人返回同码。 */
    @Test
    void approveShouldFailClosedWhenAgentOffline() {
        CommandRunEntity pending = pendingRun();
        when(runMapper.selectRunById(100L)).thenReturn(pending);
        when(runMapper.approveRun(eq(100L), eq(ACTOR), any())).thenReturn(1);
        when(transport.send(eq(SERVER_ID), anyString())).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.approve(100L, ACTOR));

        assertEquals(ErrorCode.COMMAND_AGENT_OFFLINE, exception.getErrorCode());
        verify(runMapper).markFailed(eq(100L), eq(40906), any());
        verify(runMapper, never()).markExecuting(anyLong());
    }

    /** 自动审批成功：CAS 仅按 pending 状态、不写审批人，随后下发并进入 executing。 */
    @Test
    void autoApproveShouldDispatchWithoutApprover() throws Exception {
        CommandRunEntity pending = pendingRun();
        pending.setSource(CommandRunService.SOURCE_AI);
        CommandRunEntity executing = pendingRun();
        executing.setStatus(CommandRunService.STATUS_EXECUTING);
        when(runMapper.selectRunById(100L)).thenReturn(pending, executing);
        when(runMapper.autoApproveRun(100L)).thenReturn(1);
        when(transport.send(eq(SERVER_ID), anyString())).thenReturn(true);

        CommandRunEntity result = service.autoApprove(100L);

        assertEquals(CommandRunService.STATUS_EXECUTING, result.getStatus());
        verify(runMapper).autoApproveRun(100L);
        verify(runMapper, never()).approveRun(anyLong(), anyLong(), any());
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(transport).send(eq(SERVER_ID), bodyCaptor.capture());
        assertEquals("command.execute", objectMapper.readTree(bodyCaptor.getValue()).path("type").asText());
        verify(runMapper).markExecuting(100L);
    }

    /** 自动审批遇 Agent 离线：置 failed(40906) 并抛出，与其他单条建议隔离。 */
    @Test
    void autoApproveShouldFailClosedWhenAgentOffline() {
        CommandRunEntity pending = pendingRun();
        when(runMapper.selectRunById(100L)).thenReturn(pending);
        when(runMapper.autoApproveRun(100L)).thenReturn(1);
        when(transport.send(eq(SERVER_ID), anyString())).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.autoApprove(100L));

        assertEquals(ErrorCode.COMMAND_AGENT_OFFLINE, exception.getErrorCode());
        verify(runMapper).markFailed(eq(100L), eq(40906), any());
    }

    /** 自动审批 CAS 失败（非 pending，如已被人工处理）返回状态冲突。 */
    @Test
    void autoApproveShouldFailOnNonPendingRun() {
        CommandRunEntity pending = pendingRun();
        when(runMapper.selectRunById(100L)).thenReturn(pending);
        when(runMapper.autoApproveRun(100L)).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.autoApprove(100L));

        assertEquals(ErrorCode.COMMAND_RUN_STATE_CONFLICT, exception.getErrorCode());
        verify(transport, never()).send(anyLong(), anyString());
    }

    /** 结果回填：success=true → succeeded；写 exit_code/truncated/duration；stdout 超长被截断。 */
    @Test
    void completeShouldStoreRedactedResultAndBeIdempotent() throws Exception {
        CommandRunEntity executing = pendingRun();
        executing.setStatus(CommandRunService.STATUS_EXECUTING);
        when(runMapper.selectRunByExecutionId("exec-1")).thenReturn(executing);
        when(runMapper.completeRun(eq("exec-1"), eq(CommandRunService.STATUS_SUCCEEDED),
                any(), eq(0), eq(true), eq(120L), eq(null), any())).thenReturn(1);

        String oversized = "x".repeat(200_000);
        JsonNode payload = objectMapper.readTree("""
                {"execution_id":"exec-1","server_id":7,"success":true,"exit_code":0,
                 "stdout":"%s","stderr":"","truncated":true,"duration_ms":120}
                """.formatted(oversized));

        service.complete(payload);

        ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
        verify(runMapper).completeRun(eq("exec-1"), eq(CommandRunService.STATUS_SUCCEEDED),
                resultCaptor.capture(), eq(0), eq(true), eq(120L), eq(null), any());
        assertTrue(resultCaptor.getValue().length() < 200_000,
                "oversized stdout must be truncated before persisting");
    }

    /** 未知 execution_id 的结果静默忽略；终态行的迟到结果不再更新。 */
    @Test
    void completeShouldIgnoreUnknownOrSettledRuns() {
        when(runMapper.selectRunByExecutionId("ghost")).thenReturn(null);
        service.complete(objectMapper.createObjectNode().put("execution_id", "ghost"));
        CommandRunEntity settled = pendingRun();
        settled.setStatus(CommandRunService.STATUS_SUCCEEDED);
        when(runMapper.selectRunByExecutionId("settled")).thenReturn(settled);
        service.complete(objectMapper.createObjectNode().put("execution_id", "settled"));
        verify(runMapper, never()).completeRun(anyString(), anyString(), any(), anyInt(),
                any(), anyLong(), any(), any());
    }

    /** 扫描：过期 pending 经 CAS 置 expired；超时 executing 经 CAS 置 timeout(50402)。 */
    @Test
    void sweepShouldExpireAndTimeout() {
        when(runMapper.selectExpiredIds(any(), anyInt())).thenReturn(List.of(1L));
        when(runMapper.selectTimeoutIds(any(), anyInt())).thenReturn(List.of(2L));
        when(runMapper.selectRunsByIds(List.of(2L))).thenReturn(List.of());

        service.sweep();

        verify(runMapper).markExpiredByIds(eq(List.of(1L)), any());
        verify(runMapper).markTimeoutByIds(eq(List.of(2L)), eq(50402), any());
        verify(notifier, never()).notifyCompletion(any(), anyString());
    }

    /** 结果回填后，auto 审批的运行触发事后通知并携带回填的执行摘要。 */
    @Test
    void completeShouldNotifyAutoApprovalRuns() throws Exception {
        CommandRunEntity executing = pendingRun();
        executing.setStatus(CommandRunService.STATUS_EXECUTING);
        executing.setApprovalMode(CommandRunService.APPROVAL_MODE_AUTO);
        when(runMapper.selectRunByExecutionId("exec-1")).thenReturn(executing);
        when(runMapper.completeRun(eq("exec-1"), eq(CommandRunService.STATUS_FAILED),
                any(), anyInt(), any(), anyLong(), eq(null), any())).thenReturn(1);
        JsonNode payload = objectMapper.readTree("""
                {"execution_id":"exec-1","server_id":7,"success":false,"exit_code":2,
                 "stdout":"","stderr":"oops","truncated":false,"duration_ms":40}
                """);

        service.complete(payload);

        verify(notifier).notifyCompletion(executing, CommandRunService.STATUS_FAILED);
        assertEquals(2, executing.getExitCode());
        assertEquals(40L, executing.getDurationMs());
    }

    /** 人工审批的运行回填后不触发事后通知。 */
    @Test
    void completeShouldSkipNotificationForManualRuns() throws Exception {
        CommandRunEntity executing = pendingRun();
        executing.setStatus(CommandRunService.STATUS_EXECUTING);
        executing.setApprovalMode(CommandRunService.APPROVAL_MODE_MANUAL);
        when(runMapper.selectRunByExecutionId("exec-1")).thenReturn(executing);
        when(runMapper.completeRun(eq("exec-1"), eq(CommandRunService.STATUS_SUCCEEDED),
                any(), anyInt(), any(), anyLong(), eq(null), any())).thenReturn(1);
        JsonNode payload = objectMapper.readTree("""
                {"execution_id":"exec-1","server_id":7,"success":true,"exit_code":0,
                 "stdout":"ok","stderr":"","truncated":false,"duration_ms":10}
                """);

        service.complete(payload);

        verify(notifier, never()).notifyCompletion(any(), anyString());
    }

    /** 通知器抛异常不影响结果回填主链路（尽力而为语义）。 */
    @Test
    void completeShouldSwallowNotificationFailures() throws Exception {
        CommandRunEntity executing = pendingRun();
        executing.setStatus(CommandRunService.STATUS_EXECUTING);
        executing.setApprovalMode(CommandRunService.APPROVAL_MODE_AUTO);
        when(runMapper.selectRunByExecutionId("exec-1")).thenReturn(executing);
        when(runMapper.completeRun(eq("exec-1"), eq(CommandRunService.STATUS_SUCCEEDED),
                any(), anyInt(), any(), anyLong(), eq(null), any())).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("smtp down"))
                .when(notifier).notifyCompletion(any(), anyString());
        JsonNode payload = objectMapper.readTree("""
                {"execution_id":"exec-1","server_id":7,"success":true,"exit_code":0,
                 "stdout":"ok","stderr":"","truncated":false,"duration_ms":10}
                """);

        service.complete(payload);

        verify(runMapper).completeRun(eq("exec-1"), eq(CommandRunService.STATUS_SUCCEEDED),
                any(), anyInt(), any(), anyLong(), eq(null), any());
    }

    /** 超时扫描：CAS 后按重查终态通知——仅仍为 timeout 的 auto 行收到通知。 */
    @Test
    void sweepShouldNotifyOnlyAutoApprovalTimeouts() {
        when(runMapper.selectExpiredIds(any(), anyInt())).thenReturn(List.of());
        when(runMapper.selectTimeoutIds(any(), anyInt())).thenReturn(List.of(2L, 3L));
        CommandRunEntity autoRun = pendingRun();
        autoRun.setId(2L);
        autoRun.setStatus(CommandRunService.STATUS_TIMEOUT);
        autoRun.setApprovalMode(CommandRunService.APPROVAL_MODE_AUTO);
        CommandRunEntity manualRun = pendingRun();
        manualRun.setId(3L);
        manualRun.setStatus(CommandRunService.STATUS_TIMEOUT);
        manualRun.setApprovalMode(CommandRunService.APPROVAL_MODE_MANUAL);
        when(runMapper.selectRunsByIds(List.of(2L, 3L))).thenReturn(List.of(autoRun, manualRun));

        service.sweep();

        verify(runMapper).markTimeoutByIds(eq(List.of(2L, 3L)), eq(50402), any());
        verify(notifier).notifyCompletion(autoRun, CommandRunService.STATUS_TIMEOUT);
        verify(notifier, never()).notifyCompletion(eq(manualRun), anyString());
    }

    /** 竞态回归：结果在 CAS 前已回填（重查为 succeeded）时不发 timeout 通知，通知与落库终态一致。 */
    @Test
    void sweepShouldNotNotifyRunsCompletedBeforeTimeoutCas() {
        when(runMapper.selectExpiredIds(any(), anyInt())).thenReturn(List.of());
        when(runMapper.selectTimeoutIds(any(), anyInt())).thenReturn(List.of(4L));
        CommandRunEntity completed = pendingRun();
        completed.setId(4L);
        completed.setStatus(CommandRunService.STATUS_SUCCEEDED);
        when(runMapper.selectRunsByIds(List.of(4L))).thenReturn(List.of(completed));

        service.sweep();

        verify(runMapper).markTimeoutByIds(eq(List.of(4L)), eq(50402), any());
        verify(notifier, never()).notifyCompletion(any(), anyString());
    }

    /** 拒绝仅对 pending 有效；重复拒绝或对已执行行拒绝返回状态冲突。 */
    @Test
    void rejectShouldFailOnNonPendingRun() {
        CommandRunEntity pending = pendingRun();
        when(runMapper.selectRunById(100L)).thenReturn(pending);
        when(runMapper.rejectRun(eq(100L), eq(ACTOR), any())).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.reject(100L, ACTOR));

        assertEquals(ErrorCode.COMMAND_RUN_STATE_CONFLICT, exception.getErrorCode());
    }

    private CommandRunEntity pendingRun() {
        CommandRunEntity run = new CommandRunEntity();
        run.setId(100L);
        run.setExecutionId("exec-1");
        run.setProposerId(ACTOR);
        run.setServerId(SERVER_ID);
        run.setTemplateId("service_status");
        run.setParamsJson("{\"unit\":\"nginx.service\"}");
        run.setParamsHash("a".repeat(64));
        run.setRenderedCommand("systemctl status nginx.service");
        run.setStatus(CommandRunService.STATUS_PENDING);
        run.setSource(CommandRunService.SOURCE_MANUAL);
        run.setCreatedAt(LocalDateTime.parse("2026-09-03T00:00:00"));
        run.setExpiresAt(LocalDateTime.parse("2026-09-03T00:15:00"));
        return run;
    }
}
