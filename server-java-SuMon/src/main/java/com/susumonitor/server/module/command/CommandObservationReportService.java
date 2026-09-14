package com.susumonitor.server.module.command;

import com.susumonitor.server.module.command.dto.CommandObservationAggregate;
import com.susumonitor.server.module.command.dto.TemplateUsageAggregate;
import com.susumonitor.server.module.command.mapper.CommandRunMapper;
import com.susumonitor.server.module.command.vo.CommandObservationReportVo;
import com.susumonitor.server.module.command.vo.CommandObservationReportVo.Criterion;
import com.susumonitor.server.module.command.vo.CommandObservationReportVo.Policy;
import com.susumonitor.server.module.command.vo.CommandObservationReportVo.TemplateUsage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * M2 观察期评审报告服务：对 ai_command_runs 审计行做窗口聚合，并按评审基线 v1
 * 逐项给出 PASS/FAIL/样本不足结论，供自动审批出口评审使用。
 *
 * <p>只读、查询时聚合、不落库；基线以常量定义——基线本身需要评审通过才能变更，
 * 做成运行时配置会削弱其门禁语义。所有输入仅 windowDays（Controller 已限 1-90），
 * 实际覆盖面受审计保留期（默认 30 天）约束，报告携带窗口起止时间供读者核对。</p>
 */
@Service
@ConditionalOnProperty(name = "susumonitor.ai.command.enabled", havingValue = "true")
public class CommandObservationReportService {

    /** 评审基线 v1：窗口内总运行数下限，低于该值整体结论为 insufficient。 */
    static final long MIN_SAMPLE_SIZE = 30;

    /** 评审基线 v1：自动执行失败率可评判所需的自动审批终态样本下限。 */
    static final long MIN_AUTO_SAMPLE = 10;

    /** 评审基线 v1：自动执行失败率（failed+timeout 占自动终态）上限。 */
    static final BigDecimal MAX_AUTO_FAILURE_RATE = new BigDecimal("0.05");

    /** 评审基线 v1：超时率可评判所需的已执行终态样本下限。 */
    static final long MIN_TERMINAL_SAMPLE = 10;

    /** 评审基线 v1：超时率（timeout 占已执行终态）上限。 */
    static final BigDecimal MAX_TIMEOUT_RATE = new BigDecimal("0.02");

    /** 评审基线 v1：过期率（expired 占总运行数）上限，衡量审批时效。 */
    static final BigDecimal MAX_EXPIRED_RATE = new BigDecimal("0.20");

    /** 比率字段统一保留的小数位（0-1 比率，4 位小数足够评审分辨力）。 */
    private static final int RATE_SCALE = 4;

    /** 模板用量 Top N 条数（固定常量，避免窗口大表深分页语义）。 */
    private static final int TEMPLATE_USAGE_LIMIT = 10;

    /** 策略快照的 v1 边界说明（对外输出，前端原样展示）。 */
    private static final String POLICY_NOTE =
            "当前单行策略快照；窗口内策略变更未追踪（v1 限制）";

    private final CommandRunMapper runMapper;
    private final CommandAutoApprovalPolicyService autoApprovalPolicyService;
    private final java.time.Clock clock;

    /** 注入审计 Mapper、策略服务与可注入时钟（测试固定时刻）。 */
    public CommandObservationReportService(CommandRunMapper runMapper,
            CommandAutoApprovalPolicyService autoApprovalPolicyService, java.time.Clock clock) {
        this.runMapper = runMapper;
        this.autoApprovalPolicyService = autoApprovalPolicyService;
        this.clock = clock;
    }

    /**
     * 生成观察窗口为 windowDays 天的评审报告。
     *
     * @param windowDays 回看天数（Controller 已校验 1-90）
     * @return 含聚合指标、基线核对结论与当前策略快照的只读报告
     */
    public CommandObservationReportVo generate(int windowDays) {
        LocalDateTime windowEnd = LocalDateTime.now(clock);
        LocalDateTime windowStart = windowEnd.minusDays(windowDays);

        // 无 GROUP BY 的条件聚合恒返回一行；防御空结果避免聚合字段 NPE。
        CommandObservationAggregate aggregate = orEmpty(
                runMapper.selectObservationAggregate(windowStart));
        List<TemplateUsageAggregate> usage =
                runMapper.selectTemplateUsage(windowStart, TEMPLATE_USAGE_LIMIT);

        CommandObservationReportVo report = new CommandObservationReportVo();
        report.setWindowDays(windowDays);
        report.setWindowStart(toOffset(windowStart));
        report.setWindowEnd(toOffset(windowEnd));
        report.setTotalRuns(nvl(aggregate.getTotalRuns()));

        report.setByStatus(nonZero(statusMap(aggregate)));
        report.setByApprovalMode(nonZero(Map.of(
                "auto", nvl(aggregate.getApprovalModeAuto()),
                "manual", nvl(aggregate.getApprovalModeManual()))));
        report.setByRiskLevel(nonZero(Map.of(
                "low", nvl(aggregate.getRiskLevelLow()),
                "medium", nvl(aggregate.getRiskLevelMedium()),
                "high", nvl(aggregate.getRiskLevelHigh()))));
        report.setBySource(nonZero(Map.of(
                "ai", nvl(aggregate.getSourceAi()),
                "manual", nvl(aggregate.getSourceManual()))));

        report.setAutoExecuted(nvl(aggregate.getAutoExecuted()));
        report.setAutoFailed(nvl(aggregate.getAutoFailed()));
        report.setManualExecuted(nvl(aggregate.getManualExecuted()));
        report.setManualFailed(nvl(aggregate.getManualFailed()));
        report.setDistinctServers(nvl(aggregate.getDistinctServers()));
        report.setAvgDurationMs(scale(aggregate.getAvgDurationMs()));
        report.setMaxDurationMs(aggregate.getMaxDurationMs());

        List<TemplateUsage> usageVos = new ArrayList<>();
        for (TemplateUsageAggregate row : usage) {
            TemplateUsage vo = new TemplateUsage();
            vo.setTemplateId(row.getTemplateId());
            vo.setRuns(nvl(row.getRuns()));
            usageVos.add(vo);
        }
        report.setTemplateUsage(usageVos);

        report.setPolicy(policyVo());
        List<Criterion> criteria = buildCriteria(aggregate);
        report.setCriteria(criteria);
        report.setOverall(overallOf(criteria));
        return report;
    }

    /**
     * 按评审基线 v1 组装核对项；passed 三态：true/false 判定，null 样本不足。
     */
    private List<Criterion> buildCriteria(CommandObservationAggregate aggregate) {
        long totalRuns = nvl(aggregate.getTotalRuns());
        long autoExecuted = nvl(aggregate.getAutoExecuted());
        long autoFailed = nvl(aggregate.getAutoFailed());
        long succeeded = nvl(aggregate.getStatusSucceeded());
        long failed = nvl(aggregate.getStatusFailed());
        long timeout = nvl(aggregate.getStatusTimeout());
        long terminal = succeeded + failed + timeout;
        long expired = nvl(aggregate.getStatusExpired());
        long highRiskAuto = nvl(aggregate.getHighRiskAuto());

        List<Criterion> criteria = new ArrayList<>();

        // C1 样本量：整体充分性的门禁项，恒可评判。
        criteria.add(criterion("sample_size", totalRuns, ">= 30", totalRuns >= MIN_SAMPLE_SIZE));

        // C2 自动执行失败率：自动终态样本不足时不评判（passed=null）。
        BigDecimal autoFailureRate = rate(autoFailed, autoExecuted);
        Boolean autoFailurePassed = autoExecuted < MIN_AUTO_SAMPLE
                ? null
                : autoFailureRate.compareTo(MAX_AUTO_FAILURE_RATE) <= 0;
        criteria.add(criterion("auto_failure_rate", autoFailureRate,
                "<= 0.05 且 auto 样本 >= 10", autoFailurePassed));

        // C3 超时率：衡量 Agent 通道健康度；终态样本不足时不评判。
        BigDecimal timeoutRate = rate(timeout, terminal);
        Boolean timeoutPassed = terminal < MIN_TERMINAL_SAMPLE
                ? null
                : timeoutRate.compareTo(MAX_TIMEOUT_RATE) <= 0;
        criteria.add(criterion("timeout_rate", timeoutRate, "<= 0.02", timeoutPassed));

        // C4 高风险自动执行不变量：正常业务恒为 0，>0 意味着策略被绕过或数据异常，硬 FAIL。
        criteria.add(criterion("high_risk_auto", highRiskAuto, "= 0", highRiskAuto == 0));

        // C5 过期率：审批时效/关注度；样本量与 C1 同源，不足时不评判。
        BigDecimal expiredRate = rate(expired, totalRuns);
        Boolean expiredPassed = totalRuns < MIN_SAMPLE_SIZE
                ? null
                : expiredRate.compareTo(MAX_EXPIRED_RATE) <= 0;
        criteria.add(criterion("expired_rate", expiredRate, "<= 0.20", expiredPassed));

        return criteria;
    }

    /**
     * 总体结论：sample_size 是充分性门禁（false 记为样本不足，不参与 fail 判定）；
     * 其余任一可评判项 FAIL 即 fail（fail 优先级最高，防样本不足掩盖问题）；
     * 否则样本量不达标为 insufficient；否则 pass。
     */
    private String overallOf(List<Criterion> criteria) {
        boolean sampleInsufficient = false;
        for (Criterion criterion : criteria) {
            if ("sample_size".equals(criterion.getKey())) {
                // 样本量不足走 insufficient 出口，本身不是"失败"。
                sampleInsufficient = !Boolean.TRUE.equals(criterion.getPassed());
                continue;
            }
            if (Boolean.FALSE.equals(criterion.getPassed())) {
                return "fail";
            }
        }
        return sampleInsufficient ? "insufficient" : "pass";
    }

    /** 组装单条核对项；value 为 null 时不产生比率意义，原样透传。 */
    private Criterion criterion(String key, Number value, String threshold, Boolean passed) {
        Criterion criterion = new Criterion();
        criterion.setKey(key);
        criterion.setValue(value);
        criterion.setThreshold(threshold);
        criterion.setPassed(passed);
        return criterion;
    }

    /** 当前自动审批策略快照转 VO（策略表无历史，窗口内变更不追踪）。 */
    private Policy policyVo() {
        CommandAutoApprovalPolicyService.Snapshot snapshot = autoApprovalPolicyService.get();
        Policy policy = new Policy();
        policy.setEnabled(snapshot.enabled());
        policy.setMaxRiskLevel(snapshot.maxRiskLevel().value());
        policy.setUpdatedAt(snapshot.updatedAt());
        policy.setUpdatedBy(snapshot.updatedBy());
        policy.setNote(POLICY_NOTE);
        return policy;
    }

    /** 按状态构建计数映射（含零值，由调用方过滤）。 */
    private Map<String, Long> statusMap(CommandObservationAggregate aggregate) {
        Map<String, Long> status = new LinkedHashMap<>();
        status.put("pending_approval", nvl(aggregate.getStatusPendingApproval()));
        status.put("approved", nvl(aggregate.getStatusApproved()));
        status.put("executing", nvl(aggregate.getStatusExecuting()));
        status.put("succeeded", nvl(aggregate.getStatusSucceeded()));
        status.put("failed", nvl(aggregate.getStatusFailed()));
        status.put("rejected", nvl(aggregate.getStatusRejected()));
        status.put("expired", nvl(aggregate.getStatusExpired()));
        status.put("timeout", nvl(aggregate.getStatusTimeout()));
        return status;
    }

    /** 仅保留非零项，压缩响应体并避免"0 值状态"误导读者。 */
    private Map<String, Long> nonZero(Map<String, Long> source) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : source.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /** 分子/分母比率，scale=4 HALF_UP；分母为 0 时返回 null（无比率意义）。 */
    private BigDecimal rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), RATE_SCALE, RoundingMode.HALF_UP);
    }

    /** 平均耗时统一到 4 位小数（MyBatis AVG 返回高精度 DECIMAL，截断展示噪声）。 */
    private BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    /** 聚合行空值防御：SUM(CASE) 无行返回 NULL，统一按 0 参与运算。 */
    private static long nvl(Long value) {
        return value == null ? 0L : value;
    }

    /** Mapper 意外返回 null 时退回全零聚合，保证报告结构完整。 */
    private static CommandObservationAggregate orEmpty(CommandObservationAggregate aggregate) {
        return aggregate == null ? new CommandObservationAggregate() : aggregate;
    }

    /** LocalDateTime 转契约 date-time（与既有 VO 的 UTC 偏移口径一致）。 */
    private static OffsetDateTime toOffset(LocalDateTime value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
