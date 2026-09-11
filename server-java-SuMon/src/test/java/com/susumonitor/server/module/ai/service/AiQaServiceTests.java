package com.susumonitor.server.module.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.common.vo.PageResult;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.ai.entity.AiQaRunEntity;
import com.susumonitor.server.module.ai.limit.AiQaRateLimiter;
import com.susumonitor.server.module.ai.mapper.AiQaRunMapper;
import com.susumonitor.server.module.ai.provider.AiProviderException;
import com.susumonitor.server.module.ai.provider.AiProviderResolver;
import com.susumonitor.server.module.ai.provider.AiQaAnswer;
import com.susumonitor.server.module.ai.provider.OpenAiCompatibleProvider;
import com.susumonitor.server.module.ai.provider.SpringAiChatClient;
import com.susumonitor.server.module.ai.vo.AiQaVo;
import com.susumonitor.server.module.ai.vo.AiUsageVo;
import com.susumonitor.server.module.alert.service.AlertRecordService;
import com.susumonitor.server.module.metrics.service.MetricsService;
import com.susumonitor.server.module.server.service.ServerService;
import com.susumonitor.server.module.server.vo.ServerStatusVo;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** 验证运维问答编排：kill switch、限流/预算、降级链与最小审计状态机。 */
class AiQaServiceTests {

    private static final long ACTOR_ID = 9L;
    private static final long SERVER_ID = 1L;

    private ServerService serverService;
    private MetricsService metricsService;
    private AlertRecordService alertRecordService;
    private AiProviderResolver providerResolver;
    private SpringAiChatClient chatClient;
    private OpenAiCompatibleProvider fallbackProvider;
    private AiQaRateLimiter rateLimiter;
    private AiQaRunMapper auditMapper;
    private AppProperties appProperties;
    private AiQaService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        serverService = mock(ServerService.class);
        metricsService = mock(MetricsService.class);
        alertRecordService = mock(AlertRecordService.class);
        providerResolver = mock(AiProviderResolver.class);
        chatClient = mock(SpringAiChatClient.class);
        fallbackProvider = mock(OpenAiCompatibleProvider.class);
        rateLimiter = mock(AiQaRateLimiter.class);
        auditMapper = mock(AiQaRunMapper.class);
        appProperties = new AppProperties();
        AppProperties.Ai ai = appProperties.getAi();
        ai.setEnabled(true);
        ai.setProvider("openai-compatible");
        ai.setModel("test-model");
        ai.setBaseUrl("https://provider.example");
        ai.setApiKey("test-key");
        ai.getQa().setEnabled(true);
        stubQaResolution(chatClient, fallbackProvider);
        service = new AiQaService(serverService, metricsService, alertRecordService, providerResolver,
                rateLimiter, auditMapper, appProperties, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC));
        when(auditMapper.insertRun(any(AiQaRunEntity.class))).thenReturn(1);
        when(auditMapper.completeRun(any(AiQaRunEntity.class))).thenReturn(1);
        when(auditMapper.failRun(any(AiQaRunEntity.class))).thenReturn(1);
        when(serverService.existsActive(SERVER_ID)).thenReturn(true);
    }

    /** 让解析器返回给定工具化客户端与无工具 provider 的全局兜底解析结果。 */
    private void stubQaResolution(SpringAiChatClient toolClient, OpenAiCompatibleProvider noToolsProvider) {
        when(providerResolver.resolveQaForActor(ACTOR_ID)).thenReturn(
                new AiProviderResolver.QaResolution(toolClient, noToolsProvider,
                        "openai-compatible", "test-model", AiProviderResolver.Source.GLOBAL));
    }

    /** qa kill switch 关闭时在任何数据库访问前短路 50304。 */
    @Test
    void qaDisabledShouldShortCircuit() {
        appProperties.getAi().getQa().setEnabled(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.ask(ACTOR_ID, SERVER_ID, "cpu 怎么样"));

        assertEquals(ErrorCode.AI_DISABLED_OR_REDACTION_FAILED, exception.getErrorCode());
        verify(rateLimiter, never()).checkAllowed(anyLong());
        verify(auditMapper, never()).insertRun(any());
    }

    /** 空问题与超出 qa 动态上限的问题均被 40002 拒绝。 */
    @Test
    void blankOrOversizedQuestionShouldBeRejected() {
        assertThrows(BusinessException.class, () -> service.ask(ACTOR_ID, SERVER_ID, " "));
        BusinessException oversized = assertThrows(BusinessException.class,
                () -> service.ask(ACTOR_ID, SERVER_ID, "x".repeat(2001)));
        assertEquals(ErrorCode.INVALID_REQUEST_PARAMETER, oversized.getErrorCode());
        verify(auditMapper, never()).insertRun(any());
    }

    /** 敏感词问题（ssh/terminal/凭据/外链）fail-closed 拒绝。 */
    @Test
    void sensitiveQuestionShouldBeRejected() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.ask(ACTOR_ID, SERVER_ID, "help me ssh into the server"));

        assertEquals(ErrorCode.INVALID_REQUEST_PARAMETER, exception.getErrorCode());
        verify(auditMapper, never()).insertRun(any());
    }

    /** 指定了不存在服务器时 40400。 */
    @Test
    void unknownServerShouldBeRejected() {
        when(serverService.existsActive(SERVER_ID)).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.ask(ACTOR_ID, SERVER_ID, "cpu 怎么样"));

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.getErrorCode());
    }

    /** 独立限流超限直接 42906，不建审计不调 provider。 */
    @Test
    void rateLimitedShouldBeRejected() {
        Mockito.doThrow(new BusinessException(ErrorCode.AI_RATE_LIMIT_REACHED))
                .when(rateLimiter).checkAllowed(ACTOR_ID);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.ask(ACTOR_ID, SERVER_ID, "cpu 怎么样"));

        assertEquals(ErrorCode.AI_RATE_LIMIT_REACHED, exception.getErrorCode());
        verify(auditMapper, never()).insertRun(any());
    }

    /** qa 独立按天预算耗尽后 42906。 */
    @Test
    void dailyBudgetExhaustedShouldBeRejected() {
        appProperties.getAi().getQa().setDailyTokenBudget(5);
        when(auditMapper.selectTotalTokensBetween(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(10L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.ask(ACTOR_ID, SERVER_ID, "cpu 怎么样"));

        assertEquals(ErrorCode.AI_RATE_LIMIT_REACHED, exception.getErrorCode());
    }

    /** 工具版成功：model_used=true、degraded=false，审计写 completed 与用量。 */
    @Test
    void toolPathSuccessShouldCompleteAudit() {
        AiUsageVo usage = new AiUsageVo();
        usage.setInputTokens(10);
        usage.setOutputTokens(5);
        usage.setTotalTokens(15);
        when(chatClient.ask("cpu 怎么样")).thenReturn(new AiQaAnswer("正常", usage));

        AiQaVo result = service.ask(ACTOR_ID, SERVER_ID, "cpu 怎么样");

        assertTrue(result.isModelUsed());
        assertFalse(result.isDegraded());
        assertEquals("正常", result.getAnswer());
        assertEquals("ai-qa-v1", result.getPromptVersion());
        assertEquals(15, result.getUsage().getTotalTokens());
        ArgumentCaptor<AiQaRunEntity> captor = ArgumentCaptor.forClass(AiQaRunEntity.class);
        verify(auditMapper).completeRun(captor.capture());
        assertEquals("completed", captor.getValue().getStatus());
        assertEquals(15, captor.getValue().getTotalTokens());
        assertTrue(captor.getValue().getResultJson().contains("\"answer\""));
        verify(fallbackProvider, never()).askWithoutTools(anyString(), anyString());
    }

    /** 工具版失败回退无工具单次调用：model_used=true、degraded=true。 */
    @Test
    void toolPathFailureShouldFallBackToNoTools() {
        when(chatClient.ask(anyString()))
                .thenThrow(new AiProviderException(ErrorCode.AI_PROVIDER_UNAVAILABLE));
        when(fallbackProvider.askWithoutTools(eq("cpu 怎么样"), contains("server_status")))
                .thenReturn(new AiQaAnswer("降级回答", new AiUsageVo()));
        when(serverService.status(SERVER_ID)).thenReturn(new ServerStatusVo());
        PageResult<com.susumonitor.server.module.alert.vo.AlertRecordVo> alerts = new PageResult<>();
        alerts.setItems(List.of());
        alerts.setTotal(0);
        when(alertRecordService.listRecords(eq(SERVER_ID), any(), eq(1), eq(5))).thenReturn(alerts);

        AiQaVo result = service.ask(ACTOR_ID, SERVER_ID, "cpu 怎么样");

        assertTrue(result.isModelUsed());
        assertTrue(result.isDegraded());
        assertEquals("降级回答", result.getAnswer());
        verify(auditMapper).completeRun(any(AiQaRunEntity.class));
    }

    /** 工具版与无工具版均失败时返回确定性摘要：model_used=false、degraded=true。 */
    @Test
    void bothPathsFailShouldReturnDeterministicSummary() {
        when(chatClient.ask(anyString()))
                .thenThrow(new AiProviderException(ErrorCode.AI_PROVIDER_TIMEOUT));
        when(fallbackProvider.askWithoutTools(anyString(), anyString()))
                .thenThrow(new AiProviderException(ErrorCode.AI_PROVIDER_TIMEOUT));
        ServerStatusVo status = new ServerStatusVo();
        status.setStatus("ONLINE");
        status.setAgentStatus("CONNECTED");
        when(serverService.status(SERVER_ID)).thenReturn(status);
        Mockito.doThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND))
                .when(metricsService).latest(SERVER_ID);
        com.susumonitor.server.module.alert.vo.AlertRecordVo alertRecord =
                new com.susumonitor.server.module.alert.vo.AlertRecordVo();
        PageResult<com.susumonitor.server.module.alert.vo.AlertRecordVo> alerts = new PageResult<>();
        alerts.setItems(List.of(alertRecord));
        alerts.setTotal(1);
        when(alertRecordService.listRecords(eq(SERVER_ID), any(), eq(1), eq(5))).thenReturn(alerts);

        AiQaVo result = service.ask(ACTOR_ID, SERVER_ID, "cpu 怎么样");

        assertFalse(result.isModelUsed());
        assertTrue(result.isDegraded());
        assertTrue(result.getAnswer().contains("确定性降级"));
        assertTrue(result.getAnswer().contains("ONLINE"));
        assertEquals(0, result.getUsage().getTotalTokens());
        verify(auditMapper).completeRun(any(AiQaRunEntity.class));
    }

    /** Spring AI 客户端未装配时直接走无工具路径。 */
    @Test
    void noClientShouldGoNoToolsDirectly() {
        stubQaResolution(null, fallbackProvider);
        when(fallbackProvider.askWithoutTools(anyString(), anyString()))
                .thenReturn(new AiQaAnswer("无工具回答", new AiUsageVo()));

        AiQaVo result = service.ask(ACTOR_ID, null, "整体情况如何");

        assertTrue(result.isModelUsed());
        assertTrue(result.isDegraded());
        verify(chatClient, never()).ask(anyString());
    }
}
