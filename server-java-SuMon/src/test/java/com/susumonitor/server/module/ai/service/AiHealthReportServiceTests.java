package com.susumonitor.server.module.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.ai.entity.AiHealthReportEntity;
import com.susumonitor.server.module.ai.mapper.AiHealthReportMapper;
import com.susumonitor.server.module.ai.model.AiHealthReportFacts;
import com.susumonitor.server.module.ai.notify.AiHealthReportNotifier;
import com.susumonitor.server.module.ai.provider.AiHealthReportSummary;
import com.susumonitor.server.module.ai.provider.AiProvider;
import com.susumonitor.server.module.ai.vo.AiHealthReportVo;
import com.susumonitor.server.module.ai.vo.AiUsageVo;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 验证定时健康报告编排（F3）：白名单聚合、降级不阻塞主链路、
 * 同日 UPSERT 幂等、预算/限流治理与尽力而为通知。
 */
@ExtendWith(MockitoExtension.class)
class AiHealthReportServiceTests {

    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 9, 10);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-11T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private AiProvider aiProvider;

    @Mock
    private ObjectProvider<AiProvider> aiProviderProvider;

    @Mock
    private AiHealthReportMapper reportMapper;

    @Mock
    private ObjectProvider<AiHealthReportNotifier> notifierProvider;

    @Mock
    private AiHealthReportNotifier notifier;

    private AppProperties appProperties;
    private ObjectMapper objectMapper;
    private AiHealthReportService service;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getAi().getReport().setEnabled(true);
        appProperties.getAi().getReport().setNotifyEnabled(true);
        appProperties.getAi().setModel("test-model");
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        lenient().when(aiProviderProvider.getIfAvailable()).thenReturn(aiProvider);
        lenient().when(notifierProvider.getIfAvailable()).thenReturn(notifier);
        service = new AiHealthReportService(aiProviderProvider, reportMapper, notifierProvider,
                appProperties, objectMapper, CLOCK);
    }

    /** 成功路径：聚合事实 + provider 摘要 → succeeded 落库（UPSERT）→ 尽力而为通知。 */
    @Test
    void generateShouldSucceedAndPersistAndNotify() {
        stubAggregation();
        when(aiProvider.summarizeDailyHealth(any())).thenReturn(new AiHealthReportSummary(
                "All servers healthy; one disk peak worth attention.",
                List.of("Disk peak on server #7"), List.of("Snapshot-based offline list."),
                usage()));
        when(reportMapper.upsertReport(any())).thenReturn(1);

        AiHealthReportVo vo = service.generate(REPORT_DATE, null);

        assertEquals(AiHealthReportService.STATUS_SUCCEEDED, vo.getStatus());
        assertEquals("test-model", vo.getModel());
        assertNotNull(vo.getSummary());
        assertEquals(List.of("Disk peak on server #7"), vo.getTopConcerns());
        assertEquals(30, vo.getUsage().getTotalTokens());
        ArgumentCaptor<AiHealthReportEntity> captor = ArgumentCaptor.forClass(AiHealthReportEntity.class);
        verify(reportMapper).upsertReport(captor.capture());
        assertEquals(REPORT_DATE, captor.getValue().getReportDate());
        assertEquals(AiHealthReportService.STATUS_SUCCEEDED, captor.getValue().getStatus());
        assertNotNull(captor.getValue().getResultJson());
        verify(notifier).dispatch(vo);
    }

    /** 调度路径 provider 缺席：降级为纯事实报告并落库，error_code=50304，不抛异常。 */
    @Test
    void generateShouldDegradeWhenProviderAbsentOnSchedulePath() {
        stubAggregation();
        when(aiProviderProvider.getIfAvailable()).thenReturn(null);
        when(reportMapper.upsertReport(any())).thenReturn(1);

        AiHealthReportVo vo = service.generate(REPORT_DATE, null);

        assertEquals(AiHealthReportService.STATUS_DEGRADED, vo.getStatus());
        assertNull(vo.getSummary());
        assertEquals(ErrorCode.AI_DISABLED_OR_REDACTION_FAILED.getCode(), vo.getErrorCode());
        assertNotNull(vo.getFacts());
        verify(reportMapper).upsertReport(any());
        verify(notifier).dispatch(vo);
    }

    /** 调度路径 provider 失败：降级不阻塞主链路，报告仍落库并记录错误码。 */
    @Test
    void generateShouldDegradeWhenProviderFailsOnSchedulePath() {
        stubAggregation();
        when(aiProvider.summarizeDailyHealth(any()))
                .thenThrow(new BusinessException(ErrorCode.AI_PROVIDER_TIMEOUT));
        when(reportMapper.upsertReport(any())).thenReturn(1);

        AiHealthReportVo vo = service.generate(REPORT_DATE, null);

        assertEquals(AiHealthReportService.STATUS_DEGRADED, vo.getStatus());
        assertEquals(ErrorCode.AI_PROVIDER_TIMEOUT.getCode(), vo.getErrorCode());
        verify(notifier).dispatch(vo);
    }

    /** 手动路径 provider 缺席：与调度路径一致降级为纯事实报告（error_code=50304）并落库。 */
    @Test
    void generateShouldDegradeManualTriggerWhenProviderAbsent() {
        stubAggregation();
        when(aiProviderProvider.getIfAvailable()).thenReturn(null);
        when(reportMapper.upsertReport(any())).thenReturn(1);

        AiHealthReportVo vo = service.generate(REPORT_DATE, 9L);

        assertEquals(AiHealthReportService.STATUS_DEGRADED, vo.getStatus());
        assertEquals(ErrorCode.AI_DISABLED_OR_REDACTION_FAILED.getCode(), vo.getErrorCode());
        assertNull(vo.getSummary());
        verify(reportMapper).upsertReport(any());
        verify(notifier).dispatch(vo);
    }

    /** 手动路径预算耗尽：抛 42906，不调模型不落库。 */
    @Test
    void generateShouldRejectManualTriggerWhenBudgetExhausted() {
        stubAggregation();
        appProperties.getAi().getReport().setDailyTokenBudget(100);
        when(reportMapper.sumTotalTokensBetween(any(), any())).thenReturn(200L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.generate(REPORT_DATE, 9L));

        assertEquals(ErrorCode.AI_RATE_LIMIT_REACHED, exception.getErrorCode());
        verify(aiProvider, never()).summarizeDailyHealth(any());
        verify(reportMapper, never()).upsertReport(any());
    }

    /** 调度路径预算耗尽：跳过模型调用直接降级（42906），报告仍落库。 */
    @Test
    void generateShouldDegradeWithoutModelWhenBudgetExhaustedOnSchedulePath() {
        stubAggregation();
        appProperties.getAi().getReport().setDailyTokenBudget(100);
        when(reportMapper.sumTotalTokensBetween(any(), any())).thenReturn(200L);
        when(reportMapper.upsertReport(any())).thenReturn(1);

        AiHealthReportVo vo = service.generate(REPORT_DATE, null);

        assertEquals(AiHealthReportService.STATUS_DEGRADED, vo.getStatus());
        assertEquals(ErrorCode.AI_RATE_LIMIT_REACHED.getCode(), vo.getErrorCode());
        verify(aiProvider, never()).summarizeDailyHealth(any());
        verify(reportMapper).upsertReport(any());
    }

    /** 手动路径限流：同窗口超过配额后抛 42906（限流器构造时读取配置，需重建服务）。 */
    @Test
    void generateShouldRejectManualTriggerWhenRateLimited() {
        stubAggregation();
        when(reportMapper.upsertReport(any())).thenReturn(1);
        appProperties.getAi().getReport().setRateLimitMaxRequests(1);
        AiHealthReportService tightService = new AiHealthReportService(aiProviderProvider, reportMapper,
                notifierProvider, appProperties, objectMapper, CLOCK);

        tightService.generate(REPORT_DATE, 9L);
        BusinessException exception = assertThrows(BusinessException.class,
                () -> tightService.generate(REPORT_DATE, 9L));

        assertEquals(ErrorCode.AI_RATE_LIMIT_REACHED, exception.getErrorCode());
    }

    /** 通知失败不影响生成主链路。 */
    @Test
    void generateShouldSwallowNotificationFailures() {
        stubAggregation();
        when(aiProvider.summarizeDailyHealth(any())).thenReturn(new AiHealthReportSummary(
                "ok", List.of(), List.of(), usage()));
        when(reportMapper.upsertReport(any())).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("smtp down")).when(notifier).dispatch(any());

        AiHealthReportVo vo = service.generate(REPORT_DATE, null);

        assertEquals(AiHealthReportService.STATUS_SUCCEEDED, vo.getStatus());
    }

    /** get：result_json 损坏时退回结构化列，回看仍可用。 */
    @Test
    void getShouldFallBackToColumnsWhenResultJsonCorrupted() {
        AiHealthReportEntity entity = new AiHealthReportEntity();
        entity.setId(5L);
        entity.setReportDate(REPORT_DATE);
        entity.setStatus(AiHealthReportService.STATUS_SUCCEEDED);
        entity.setProvider("openai-compatible");
        entity.setModel("test-model");
        entity.setPromptVersion("ai-health-report-v1");
        entity.setSummary("kept summary");
        entity.setTotalTokens(30);
        entity.setResultJson("{not-json");
        when(reportMapper.selectById(5L)).thenReturn(entity);

        AiHealthReportVo vo = service.get(5L);

        assertEquals("kept summary", vo.getSummary());
        assertEquals(30, vo.getUsage().getTotalTokens());
    }

    /** get：不存在的报告返回 40404。 */
    @Test
    void getShouldRejectUnknownId() {
        when(reportMapper.selectById(404L)).thenReturn(null);
        BusinessException exception = assertThrows(BusinessException.class, () -> service.get(404L));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.getErrorCode());
    }

    /** 聚合事实白名单：离线清单/Top 告警服务器按行映射，无 host/IP 等敏感列。 */
    @Test
    void aggregateShouldMapWhitelistRowsOnly() {
        stubAggregation();
        when(aiProvider.summarizeDailyHealth(any())).thenAnswer(invocation -> {
            AiHealthReportFacts facts = invocation.getArgument(0);
            assertEquals("2026-09-10", facts.reportDate());
            assertEquals(3, facts.serverInventory().totalCount());
            assertEquals(1, facts.serverInventory().offlineCount());
            assertEquals(66.7, facts.serverInventory().onlineRate(), 0.0001);
            assertEquals(1, facts.offlineServers().size());
            assertEquals("edge-9", facts.offlineServers().get(0).serverName());
            assertEquals(2, facts.alertStatistics().totalTriggered());
            assertEquals(1, facts.alertStatistics().topServers().size());
            return new AiHealthReportSummary("ok", List.of(), List.of(), usage());
        });
        when(reportMapper.upsertReport(any())).thenReturn(1);

        service.generate(REPORT_DATE, null);
    }

    /** 桩定全部聚合查询的返回（HashMap 模拟 MyBatis Map 行）。 */
    private void stubAggregation() {
        Map<String, Object> inventory = new HashMap<>();
        inventory.put("total_count", 3L);
        inventory.put("online_count", 2L);
        when(reportMapper.selectServerInventory()).thenReturn(inventory);

        Map<String, Object> offline = new HashMap<>();
        offline.put("server_id", 9L);
        offline.put("server_name", "edge-9");
        offline.put("agent_status", "offline");
        offline.put("last_heartbeat_at", "2026-09-10 21:00:00");
        when(reportMapper.selectOfflineServers(50)).thenReturn(List.of(offline));

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("avg_cpu_percent", 41.5);
        metrics.put("max_cpu_percent", 88.0);
        metrics.put("avg_memory_percent", 55.0);
        metrics.put("max_memory_percent", 70.0);
        metrics.put("avg_disk_percent", 60.0);
        metrics.put("max_disk_percent", 91.0);
        when(reportMapper.selectMetricAggregates(any(), any())).thenReturn(metrics);
        when(reportMapper.selectMaxCpuServerId(any(), any())).thenReturn(7L);
        when(reportMapper.selectMaxMemoryServerId(any(), any())).thenReturn(8L);
        when(reportMapper.selectMaxDiskServerId(any(), any())).thenReturn(7L);

        Map<String, Object> alerts = new HashMap<>();
        alerts.put("total_triggered", 2L);
        alerts.put("critical_count", 1L);
        alerts.put("warning_count", 1L);
        alerts.put("resolved_count", 1L);
        when(reportMapper.selectAlertStatistics(any(), any())).thenReturn(alerts);

        Map<String, Object> topServer = new HashMap<>();
        topServer.put("server_id", 7L);
        topServer.put("server_name", "db-7");
        topServer.put("alert_count", 2L);
        topServer.put("critical_count", 1L);
        when(reportMapper.selectTopAlertServers(any(), any(), any(Integer.class)))
                .thenReturn(List.of(topServer));
    }

    private AiUsageVo usage() {
        AiUsageVo usage = new AiUsageVo();
        usage.setInputTokens(20);
        usage.setOutputTokens(10);
        usage.setTotalTokens(30);
        return usage;
    }
}
