package com.susumonitor.server.module.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.ai.entity.AiAlertExplanationEntity;
import com.susumonitor.server.module.ai.mapper.AiAlertExplanationMapper;
import com.susumonitor.server.module.ai.model.AiAlertFacts;
import com.susumonitor.server.module.ai.provider.AiProvider;
import com.susumonitor.server.module.ai.vo.AiAlertExplanationVo;
import com.susumonitor.server.module.ai.vo.AiUsageVo;
import com.susumonitor.server.module.metrics.service.MetricsService;
import com.susumonitor.server.module.metrics.vo.MetricsLatestVo;
import com.susumonitor.server.module.server.service.ServerService;
import com.susumonitor.server.module.server.vo.ServerStatusVo;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/** 验证 AI 告警解释编排：fail-closed 开关、当日预算、白名单上下文与内容复核。 */
@ExtendWith(MockitoExtension.class)
class AiAlertExplanationServiceTests {

    private static final long RECORD_ID = 789L;
    private static final long SERVER_ID = 123L;

    @Mock
    private AiProvider aiProvider;

    @Mock
    private ObjectProvider<AiProvider> aiProviderProvider;

    @Mock
    private AiAlertExplanationMapper explanationMapper;

    @Mock
    private ServerService serverService;

    @Mock
    private MetricsService metricsService;

    private AppProperties appProperties;

    private ObjectMapper objectMapper;

    private AiAlertExplanationService service;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getAi().getExplanation().setEnabled(true);
        appProperties.getAi().setModel("test-model");
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new AiAlertExplanationService(aiProviderProvider, explanationMapper, serverService,
                metricsService, appProperties, objectMapper,
                Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC));
        lenient().when(aiProviderProvider.getIfAvailable()).thenReturn(aiProvider);
        lenient().when(serverService.status(SERVER_ID)).thenReturn(serverStatus());
        lenient().when(metricsService.latest(SERVER_ID)).thenReturn(latest());
    }

    /** 开关关闭时 fail-closed：不访问服务器/指标，不调用 provider。 */
    @Test
    void explainShouldFailClosedWhenDisabled() {
        appProperties.getAi().getExplanation().setEnabled(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.explain(facts()));

        assertEquals(ErrorCode.AI_DISABLED_OR_REDACTION_FAILED, exception.getErrorCode());
        verify(aiProvider, never()).explainAlert(any(), any());
    }

    /** ai.enabled=false 导致 provider bean 缺席时 fail-closed（50304）。 */
    @Test
    void explainShouldFailClosedWhenProviderMissing() {
        when(aiProviderProvider.getIfAvailable()).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.explain(facts()));

        assertEquals(ErrorCode.AI_DISABLED_OR_REDACTION_FAILED, exception.getErrorCode());
        verify(metricsService, never()).latest(anyLong());
    }

    /** 当日已落库 token 达到预算上限时拒绝新解释（42906）。 */
    @Test
    void explainShouldRejectWhenDailyBudgetExhausted() {
        appProperties.getAi().getExplanation().setDailyTokenBudget(100);
        when(explanationMapper.sumTotalTokensBetween(any(), any())).thenReturn(100L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.explain(facts()));

        assertEquals(ErrorCode.AI_RATE_LIMIT_REACHED, exception.getErrorCode());
        verify(aiProvider, never()).explainAlert(any(), any());
    }

    /** 正常路径：provider 结果回填 provider/model/prompt_version/record_id，落库行含序列化 JSON。 */
    @Test
    void explainShouldDecorateAndSaveResult() {
        when(aiProvider.explainAlert(any(), any())).thenReturn(explanation());
        when(explanationMapper.insert(any())).thenReturn(1);

        AiAlertExplanationVo result = service.explain(facts());
        service.save("event-1", facts(), result, 120L);

        assertEquals("openai-compatible", result.getProvider());
        assertEquals("test-model", result.getModel());
        assertEquals(AiAlertExplanationService.PROMPT_VERSION, result.getPromptVersion());
        assertEquals(RECORD_ID, result.getRecordId());
        ArgumentCaptor<AiAlertExplanationEntity> captor =
                ArgumentCaptor.forClass(AiAlertExplanationEntity.class);
        verify(explanationMapper).insert(captor.capture());
        AiAlertExplanationEntity entity = captor.getValue();
        assertEquals(RECORD_ID, entity.getAlertRecordId());
        assertEquals(SERVER_ID, entity.getServerId());
        assertEquals(456L, entity.getRuleId());
        assertEquals("event-1", entity.getEventId());
        assertEquals("CPU elevated due to sustained load.", entity.getSummary());
        assertEquals(15, entity.getTotalTokens());
        assertNotNull(entity.getResultJson());
    }

    /** 建议列表中出现可执行指令字样时整体拒绝（50305），不落库。 */
    @Test
    void explainShouldRejectActionInstructionInSuggestions() {
        AiAlertExplanationVo explanation = explanation();
        explanation.setSuggestions(List.of("run command systemctl restart nginx"));
        when(aiProvider.explainAlert(any(), any())).thenReturn(explanation);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.explain(facts()));

        assertEquals(ErrorCode.AI_RESPONSE_INVALID, exception.getErrorCode());
        verify(explanationMapper, never()).insert(any());
    }

    /** summary 空白由 provider 端结构校验拒绝（服务端 mock 之外的职责），此处不再重复覆盖。 */

    /** 回看查询：result_json 可解析为 VO。 */
    @Test
    void getByRecordIdShouldParseStoredJson() {
        AiAlertExplanationEntity entity = new AiAlertExplanationEntity();
        entity.setAlertRecordId(RECORD_ID);
        entity.setSummary("stored summary");
        entity.setResultJson("{\"record_id\":789,\"summary\":\"parsed summary\",\"provider\":\"openai-compatible\","
                + "\"model\":\"test-model\",\"prompt_version\":\"ai-alert-explanation-v1\"}");
        when(explanationMapper.selectByRecordId(RECORD_ID)).thenReturn(entity);

        AiAlertExplanationVo result = service.getByRecordId(RECORD_ID);

        assertEquals("parsed summary", result.getSummary());
        assertEquals(RECORD_ID, result.getRecordId());
    }

    /** 无解释时返回 null（由端点转换为 404）。 */
    @Test
    void getByRecordIdShouldReturnNullWhenMissing() {
        when(explanationMapper.selectByRecordId(RECORD_ID)).thenReturn(null);

        assertNull(service.getByRecordId(RECORD_ID));
    }

    private AiAlertFacts facts() {
        return new AiAlertFacts(RECORD_ID, 456L, SERVER_ID, "cpu",
                new BigDecimal("92.5"), new BigDecimal("80.0"), "warning", "2026-09-04T11:55:00Z");
    }

    private AiAlertExplanationVo explanation() {
        AiAlertExplanationVo vo = new AiAlertExplanationVo();
        vo.setSummary("CPU elevated due to sustained load.");
        vo.setPossibleCauses(List.of("Application workload increase."));
        vo.setImpact(List.of("Risk of resource exhaustion."));
        vo.setSuggestions(List.of("Review CPU trend and recent deployments."));
        vo.setLimitations(List.of("Advisory only; based on latest metrics."));
        AiUsageVo usage = new AiUsageVo();
        usage.setInputTokens(10);
        usage.setOutputTokens(5);
        usage.setTotalTokens(15);
        vo.setUsage(usage);
        return vo;
    }

    private ServerStatusVo serverStatus() {
        ServerStatusVo vo = new ServerStatusVo();
        vo.setServerId(SERVER_ID);
        vo.setStatus("online");
        vo.setAgentStatus("online");
        return vo;
    }

    private MetricsLatestVo latest() {
        MetricsLatestVo vo = new MetricsLatestVo();
        vo.setServerId(SERVER_ID);
        vo.setCpuPercent(new BigDecimal("92.5"));
        vo.setMemoryPercent(new BigDecimal("61.2"));
        vo.setDiskPercent(new BigDecimal("55.0"));
        vo.setLoadAvg(new BigDecimal("1.5"));
        vo.setCollectedAt(OffsetDateTime.parse("2026-09-04T11:59:58Z"));
        return vo;
    }
}
