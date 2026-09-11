package com.susumonitor.server.module.ai.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.common.vo.PageResult;
import com.susumonitor.server.module.alert.service.AlertRecordService;
import com.susumonitor.server.module.command.AiCommandSuggestionService;
import com.susumonitor.server.module.command.CommandTemplateRegistry;
import com.susumonitor.server.module.command.entity.CommandRunEntity;
import com.susumonitor.server.module.metrics.service.MetricsService;
import com.susumonitor.server.module.server.service.ServerService;
import com.susumonitor.server.module.server.vo.ServerStatusVo;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

/** 验证只读工具注册表的参数边界、白名单、命令提案绑定与轮次预算。 */
class AiReadOnlyToolsTests {

    private static final long SERVER_ID = 1L;

    private ServerService serverService;
    private MetricsService metricsService;
    private AlertRecordService alertRecordService;
    private ObjectProvider<CommandTemplateRegistry> templateRegistryProvider;
    private ObjectProvider<AiCommandSuggestionService> suggestionServiceProvider;
    private AiCommandSuggestionService suggestionService;
    private AiReadOnlyTools tools;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        serverService = mock(ServerService.class);
        metricsService = mock(MetricsService.class);
        alertRecordService = mock(AlertRecordService.class);
        templateRegistryProvider = mock(ObjectProvider.class);
        suggestionServiceProvider = mock(ObjectProvider.class);
        suggestionService = mock(AiCommandSuggestionService.class);
        tools = new AiReadOnlyTools(serverService, metricsService, alertRecordService,
                templateRegistryProvider, suggestionServiceProvider, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        AiQaToolContext.close();
    }

    /** 上下文未 open 时工具一律拒绝执行（防御性，正常链路不会发生）。 */
    @Test
    void toolWithoutContextShouldThrow() {
        assertThrows(IllegalStateException.class, () -> tools.getServerStatus(SERVER_ID));
    }

    /** 状态工具正常返回 JSON 并记录调用审计。 */
    @Test
    void getServerStatusShouldReturnJsonAndRecord() {
        AiQaToolContext.open(9L, 6);
        ServerStatusVo status = new ServerStatusVo();
        status.setStatus("ONLINE");
        status.setAgentStatus("CONNECTED");
        when(serverService.status(SERVER_ID)).thenReturn(status);

        String result = tools.getServerStatus(SERVER_ID);

        assertTrue(result.contains("ONLINE"));
        assertEquals(1, AiQaToolContext.current().calls().size());
        assertEquals("get_server_status", AiQaToolContext.current().calls().get(0).getTool());
    }

    /** 非法 server_id（注入 fixture：负数/零/空）返回错误 JSON 供模型纠正，不抛出。 */
    @Test
    void invalidServerIdShouldReturnErrorJson() {
        AiQaToolContext.open(9L, 6);

        String negative = tools.getServerStatus(-1L);
        String zero = tools.getLatestMetrics(0L);

        assertTrue(negative.contains("invalid_request_parameter"));
        assertTrue(zero.contains("invalid_request_parameter"));
        verify(serverService, never()).status(anyLong());
    }

    /** 指标历史工具对 minutes/maxPoints 做服务端 clamp，模型无法放大窗口。 */
    @Test
    void metricsHistoryShouldClampBounds() {
        AiQaToolContext.open(9L, 6);
        PageResult<com.susumonitor.server.module.metrics.vo.MetricsHistoryVo> page = new PageResult<>();
        page.setItems(List.of());
        page.setTotal(0);
        when(metricsService.history(eq(SERVER_ID), any(), any(), eq(1), eq(50))).thenReturn(page);

        tools.getMetricsHistory(SERVER_ID, 9999, 9999);

        verify(metricsService).history(eq(SERVER_ID), any(), any(), eq(1), eq(50));
    }

    /** 告警状态过滤白名单外的值（注入 fixture）被归一为不过滤。 */
    @Test
    void alertStatusShouldBeWhitelisted() {
        AiQaToolContext.open(9L, 6);
        PageResult<com.susumonitor.server.module.alert.vo.AlertRecordVo> page = new PageResult<>();
        page.setItems(List.of());
        page.setTotal(0);
        when(alertRecordService.listRecords(isNull(), isNull(), eq(5), eq(20))).thenReturn(page);

        String result = tools.listAlertRecords(null, "DROPPED OR 1=1", 99, 99);

        verify(alertRecordService).listRecords(isNull(), isNull(), eq(5), eq(20));
        assertTrue(result.contains("items"));
    }

    /** 命令域关闭时模板与提案工具返回 disabled 说明。 */
    @Test
    void commandToolsShouldReportDisabled() {
        AiQaToolContext.open(9L, 6);
        when(templateRegistryProvider.getIfAvailable()).thenReturn(null);
        when(suggestionServiceProvider.getIfAvailable()).thenReturn(null);

        assertTrue(tools.listCommandTemplates().contains("command domain disabled"));
        assertTrue(tools.proposeCommandRun(SERVER_ID, "check disk").contains("command domain disabled"));
    }

    /** 提案工具把发起人绑定到 ThreadLocal 中的管理员（模型无法伪造 proposer）。 */
    @Test
    void proposeShouldBindActorFromContext() {
        AiQaToolContext.open(9L, 6);
        when(suggestionServiceProvider.getIfAvailable()).thenReturn(suggestionService);
        CommandRunEntity run = new CommandRunEntity();
        run.setExecutionId("exec-1");
        run.setTemplateId("disk_free");
        run.setRenderedCommand("df -h");
        run.setStatus("pending_approval");
        when(suggestionService.suggestAndCreate(9L, SERVER_ID, "check disk")).thenReturn(List.of(run));

        String result = tools.proposeCommandRun(SERVER_ID, "check disk");

        assertTrue(result.contains("exec-1"));
        assertTrue(result.contains("pending_approval"));
        assertTrue(result.contains("nothing is executed"));
        verify(suggestionService).suggestAndCreate(9L, SERVER_ID, "check disk");
    }

    /** 超长意图（注入 fixture）被拒绝，不进入提案链路。 */
    @Test
    void proposeShouldRejectOversizedIntent() {
        AiQaToolContext.open(9L, 6);
        when(suggestionServiceProvider.getIfAvailable()).thenReturn(suggestionService);

        String result = tools.proposeCommandRun(SERVER_ID, "x".repeat(501));

        assertTrue(result.contains("invalid intent"));
        verify(suggestionService, never()).suggestAndCreate(anyLong(), anyLong(), any());
    }

    /** 提案内部业务异常被转为错误 JSON，不中断模型对话。 */
    @Test
    void proposeBusinessErrorShouldReturnErrorJson() {
        AiQaToolContext.open(9L, 6);
        when(suggestionServiceProvider.getIfAvailable()).thenReturn(suggestionService);
        when(suggestionService.suggestAndCreate(anyLong(), anyLong(), any()))
                .thenThrow(new com.susumonitor.server.common.BusinessException(
                        com.susumonitor.server.common.ErrorCode.AI_DISABLED_OR_REDACTION_FAILED));

        String result = tools.proposeCommandRun(SERVER_ID, "check disk");

        assertTrue(result.contains("error"));
    }

    /** 单次问答的工具轮次预算耗尽后抛异常终止工具循环。 */
    @Test
    void iterationBudgetShouldThrow() {
        AiQaToolContext.open(9L, 1);
        when(serverService.status(SERVER_ID)).thenReturn(new ServerStatusVo());

        tools.getServerStatus(SERVER_ID);
        assertThrows(IllegalStateException.class, () -> tools.getServerStatus(SERVER_ID));
    }

    /** 调用审计条数有硬上限，超出后静默丢弃但执行不受影响。 */
    @Test
    void recordedCallsShouldBeCapped() {
        AiQaToolContext.open(9L, 60);
        when(serverService.status(SERVER_ID)).thenReturn(new ServerStatusVo());

        for (int i = 0; i < 55; i++) {
            tools.getServerStatus(SERVER_ID);
        }

        assertEquals(50, AiQaToolContext.current().calls().size());
    }
}
