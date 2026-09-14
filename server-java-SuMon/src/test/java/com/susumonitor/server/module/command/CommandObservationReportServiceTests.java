package com.susumonitor.server.module.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.susumonitor.server.module.command.dto.CommandObservationAggregate;
import com.susumonitor.server.module.command.dto.TemplateUsageAggregate;
import com.susumonitor.server.module.command.mapper.CommandRunMapper;
import com.susumonitor.server.module.command.vo.CommandObservationReportVo;
import com.susumonitor.server.module.command.vo.CommandObservationReportVo.Criterion;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 观察期评审报告服务基线判定回归：固定时钟驱动，验证窗口传递、
 * 比率计算、三态核对项与 overall 优先级（fail &gt; insufficient &gt; pass）。
 */
class CommandObservationReportServiceTests {

    /** 固定"当前时刻"，窗口起点断言以此为基准。 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 15, 2, 0, 0);

    private CommandRunMapper runMapper;
    private CommandAutoApprovalPolicyService autoApprovalPolicyService;
    private CommandObservationReportService service;

    @BeforeEach
    void setUp() {
        runMapper = Mockito.mock(CommandRunMapper.class);
        autoApprovalPolicyService = Mockito.mock(CommandAutoApprovalPolicyService.class);
        Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        service = new CommandObservationReportService(runMapper, autoApprovalPolicyService, clock);
        // 策略快照默认返回启用态，供 policy 节点断言。
        when(autoApprovalPolicyService.get()).thenReturn(new CommandAutoApprovalPolicyService.Snapshot(
                true, true, CommandRiskLevel.MEDIUM, null, 2L));
        when(runMapper.selectTemplateUsage(Mockito.any(), Mockito.anyInt()))
                .thenReturn(List.of());
    }

    /** 构造全零聚合行，用例按需覆盖关注字段。 */
    private CommandObservationAggregate emptyAggregate() {
        return new CommandObservationAggregate();
    }

    private Criterion criterion(CommandObservationReportVo report, String key) {
        return report.getCriteria().stream()
                .filter(item -> key.equals(item.getKey()))
                .findFirst()
                .orElseThrow();
    }

    /** 基线全达标的健康样本 → overall=pass，各比率精确到 4 位小数。 */
    @Test
    void healthyWindowShouldPass() {
        CommandObservationAggregate aggregate = emptyAggregate();
        aggregate.setTotalRuns(100L);
        aggregate.setStatusSucceeded(93L);
        aggregate.setStatusFailed(2L);
        aggregate.setStatusTimeout(0L);
        aggregate.setStatusExpired(5L);
        aggregate.setAutoExecuted(20L);
        aggregate.setAutoFailed(0L);
        aggregate.setManualExecuted(75L);
        aggregate.setManualFailed(2L);
        aggregate.setDistinctServers(4L);
        aggregate.setAvgDurationMs(new BigDecimal("812.3456"));
        aggregate.setMaxDurationMs(4300L);
        when(runMapper.selectObservationAggregate(Mockito.any())).thenReturn(aggregate);

        CommandObservationReportVo report = service.generate(14);

        assertThat(report.getOverall()).isEqualTo("pass");
        assertThat(report.getTotalRuns()).isEqualTo(100L);
        assertThat(report.getWindowStart()).isEqualTo(NOW.minusDays(14).atOffset(ZoneOffset.UTC));
        assertThat(report.getWindowEnd()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
        assertThat(criterion(report, "sample_size").getPassed()).isTrue();
        // 失败率 0/20 = 0.0000；超时率 0/95 = 0.0000；过期率 5/100 = 0.0500。
        assertThat(criterion(report, "auto_failure_rate").getPassed()).isTrue();
        assertThat((BigDecimal) criterion(report, "auto_failure_rate").getValue())
                .isEqualByComparingTo(new BigDecimal("0.0000"));
        assertThat(criterion(report, "timeout_rate").getPassed()).isTrue();
        assertThat((BigDecimal) criterion(report, "expired_rate").getValue())
                .isEqualByComparingTo(new BigDecimal("0.0500"));
        assertThat(criterion(report, "high_risk_auto").getPassed()).isTrue();
        // 平均耗时统一 4 位小数。
        assertThat(report.getAvgDurationMs()).isEqualByComparingTo(new BigDecimal("812.3456"));
        assertThat(report.getMaxDurationMs()).isEqualTo(4300L);
    }

    /** 窗口参数按下推天数计算并透传给 Mapper。 */
    @Test
    void windowDaysShouldBePassedToMapper() {
        when(runMapper.selectObservationAggregate(Mockito.any())).thenReturn(emptyAggregate());
        service.generate(7);
        verify(runMapper).selectObservationAggregate(NOW.minusDays(7));
    }

    /** 自动执行失败率超基线 → 该项 FAIL 且 overall=fail。 */
    @Test
    void autoFailureRateAboveBaselineShouldFail() {
        CommandObservationAggregate aggregate = emptyAggregate();
        aggregate.setTotalRuns(100L);
        aggregate.setStatusSucceeded(80L);
        aggregate.setStatusFailed(10L);
        aggregate.setStatusTimeout(10L);
        aggregate.setStatusExpired(0L);
        // 自动终态 20 例中 3 例失败 = 15% > 5%。
        aggregate.setAutoExecuted(20L);
        aggregate.setAutoFailed(3L);
        when(runMapper.selectObservationAggregate(Mockito.any())).thenReturn(aggregate);

        CommandObservationReportVo report = service.generate(14);

        assertThat(report.getOverall()).isEqualTo("fail");
        assertThat(criterion(report, "auto_failure_rate").getPassed()).isFalse();
        assertThat((BigDecimal) criterion(report, "auto_failure_rate").getValue())
                .isEqualByComparingTo(new BigDecimal("0.1500"));
        assertThat(report.getAutoExecuted()).isEqualTo(20L);
        assertThat(report.getAutoFailed()).isEqualTo(3L);
    }

    /** 自动终态样本不足 10 例 → 该项 passed=null（不评判），但样本量达标整体 pass。 */
    @Test
    void autoSampleBelowThresholdShouldBeUnjudged() {
        CommandObservationAggregate aggregate = emptyAggregate();
        aggregate.setTotalRuns(50L);
        aggregate.setStatusSucceeded(40L);
        aggregate.setStatusFailed(5L);
        aggregate.setStatusExpired(5L);
        aggregate.setAutoExecuted(5L);
        aggregate.setAutoFailed(1L);
        when(runMapper.selectObservationAggregate(Mockito.any())).thenReturn(aggregate);

        CommandObservationReportVo report = service.generate(14);

        assertThat(report.getOverall()).isEqualTo("pass");
        assertThat(criterion(report, "auto_failure_rate").getPassed()).isNull();
        assertThat((BigDecimal) criterion(report, "auto_failure_rate").getValue())
                .isEqualByComparingTo(new BigDecimal("0.2000"));
    }

    /** 总样本不足 30 → overall=insufficient，过期率与样本量项不判定。 */
    @Test
    void insufficientSampleShouldBeInsufficient() {
        CommandObservationAggregate aggregate = emptyAggregate();
        aggregate.setTotalRuns(12L);
        aggregate.setStatusSucceeded(10L);
        aggregate.setStatusExpired(2L);
        when(runMapper.selectObservationAggregate(Mockito.any())).thenReturn(aggregate);

        CommandObservationReportVo report = service.generate(30);

        assertThat(report.getOverall()).isEqualTo("insufficient");
        assertThat(report.getWindowDays()).isEqualTo(30);
        assertThat(criterion(report, "sample_size").getPassed()).isFalse();
        assertThat(criterion(report, "expired_rate").getPassed()).isNull();
    }

    /** 高风险自动执行不变量被破坏（>0）→ 即使样本不足也整体 fail（fail 优先）。 */
    @Test
    void highRiskAutoShouldOverrideInsufficient() {
        CommandObservationAggregate aggregate = emptyAggregate();
        aggregate.setTotalRuns(5L);
        aggregate.setHighRiskAuto(2L);
        when(runMapper.selectObservationAggregate(Mockito.any())).thenReturn(aggregate);

        CommandObservationReportVo report = service.generate(14);

        assertThat(report.getOverall()).isEqualTo("fail");
        assertThat(criterion(report, "high_risk_auto").getPassed()).isFalse();
        assertThat(criterion(report, "high_risk_auto").getValue()).isEqualTo(2L);
    }

    /** 空窗口：分母为 0 的比率项 value=null 且不 NPE，零值状态不输出，overall=insufficient。 */
    @Test
    void emptyWindowShouldYieldNullRatesAndSparseMaps() {
        when(runMapper.selectObservationAggregate(Mockito.any())).thenReturn(emptyAggregate());

        CommandObservationReportVo report = service.generate(14);

        assertThat(report.getOverall()).isEqualTo("insufficient");
        assertThat((BigDecimal) criterion(report, "auto_failure_rate").getValue()).isNull();
        assertThat((BigDecimal) criterion(report, "timeout_rate").getValue()).isNull();
        assertThat((BigDecimal) criterion(report, "expired_rate").getValue()).isNull();
        assertThat(report.getByStatus()).isEmpty();
        assertThat(report.getByApprovalMode()).isEmpty();
        assertThat(report.getByRiskLevel()).isEmpty();
        assertThat(report.getBySource()).isEmpty();
        assertThat(report.getAvgDurationMs()).isNull();
        assertThat(report.getMaxDurationMs()).isNull();
        assertThat(report.getAutoExecuted()).isEqualTo(0L);
    }

    /** 策略快照与模板用量随报告输出，note 携带 v1 边界说明。 */
    @Test
    void policySnapshotAndTemplateUsageShouldBeIncluded() {
        CommandObservationAggregate aggregate = emptyAggregate();
        aggregate.setTotalRuns(40L);
        when(runMapper.selectObservationAggregate(Mockito.any())).thenReturn(aggregate);
        TemplateUsageAggregate usage = new TemplateUsageAggregate();
        usage.setTemplateId("disk_free");
        usage.setRuns(25L);
        when(runMapper.selectTemplateUsage(Mockito.any(), Mockito.eq(10)))
                .thenReturn(List.of(usage));

        CommandObservationReportVo report = service.generate(14);

        assertThat(report.getPolicy().getEnabled()).isTrue();
        assertThat(report.getPolicy().getMaxRiskLevel()).isEqualTo("medium");
        assertThat(report.getPolicy().getNote()).isNotBlank();
        assertThat(report.getTemplateUsage()).hasSize(1);
        assertThat(report.getTemplateUsage().get(0).getTemplateId()).isEqualTo("disk_free");
        assertThat(report.getTemplateUsage().get(0).getRuns()).isEqualTo(25L);
        // 核对项固定 5 条，key 集合稳定（前端按 key 映射中文标签的契约前提）。
        assertThat(report.getCriteria()).hasSize(5);
        assertThat(report.getCriteria().stream().map(Criterion::getKey).collect(Collectors.toSet()))
                .isEqualTo(Map.of(
                        "sample_size", 1, "auto_failure_rate", 1, "timeout_rate", 1,
                        "high_risk_auto", 1, "expired_rate", 1).keySet());
    }
}
