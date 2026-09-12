package com.susumonitor.server.module.ai.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.common.limit.FixedWindowRateLimiter;
import com.susumonitor.server.common.vo.PageResult;
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
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 编排定时健康报告（F3）：只读聚合 → 预算/限流 → 单次 provider 摘要 →
 * 降级兜底 → 按自然日 UPSERT 落库 → 尽力而为通知。
 *
 * <p>降级语义：provider 缺席/失败或调度路径预算耗尽时，报告仍以聚合事实落库
 * （{@code status=degraded}，summary 为空并记录 error_code），绝不阻塞报告主链路；
 * 手动触发路径遇预算/限流耗尽则直接抛业务异常，让管理员得到即时反馈。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "susumonitor.ai.report.enabled", havingValue = "true")
public class AiHealthReportService {

    /** 报告生成成功（含 LLM 摘要）。 */
    public static final String STATUS_SUCCEEDED = "succeeded";
    /** 报告降级（仅聚合事实，无 LLM 摘要）。 */
    public static final String STATUS_DEGRADED = "degraded";

    /** 离线服务器与 Top 告警服务器清单的条数上限（控制 prompt 与载荷规模）。 */
    private static final int MAX_LISTED_SERVERS = 50;

    private final ObjectProvider<AiProvider> aiProvider;
    private final AiHealthReportMapper reportMapper;
    private final ObjectProvider<AiHealthReportNotifier> notifier;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final FixedWindowRateLimiter rateLimiter;
    private final Semaphore permits;

    /** 注入模型端口（可缺席）、报告存储、通知器（可缺席）与治理组件。 */
    public AiHealthReportService(ObjectProvider<AiProvider> aiProvider,
            AiHealthReportMapper reportMapper, ObjectProvider<AiHealthReportNotifier> notifier,
            AppProperties appProperties, ObjectMapper objectMapper, Clock clock) {
        this.aiProvider = aiProvider;
        this.reportMapper = reportMapper;
        this.notifier = notifier;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        AppProperties.Ai.Report config = appProperties.getAi().getReport();
        this.rateLimiter = new FixedWindowRateLimiter(config.getRateLimitMaxRequests(),
                Duration.ofSeconds(config.getRateLimitWindowSeconds()), clock);
        this.permits = new Semaphore(appProperties.getAi().getMaxConcurrentRequests());
    }

    /**
     * 生成（或重生成）指定自然日的健康报告：聚合 → 摘要 → UPSERT → 通知。
     *
     * @param reportDate 报告覆盖的自然日（调度路径为昨日；手动触发可指定历史日期）
     * @param actorId 触发者；null 表示调度器触发（预算/并发耗尽走降级而非抛错）
     * @return 已落库的报告视图
     */
    public AiHealthReportVo generate(LocalDate reportDate, Long actorId) {
        AppProperties.Ai.Report config = appProperties.getAi().getReport();
        // kill switch 短路：Bean 装配已由开关决定，此处防御运行期配置误用。
        if (!config.isEnabled()) {
            throw new BusinessException(ErrorCode.AI_DISABLED_OR_REDACTION_FAILED);
        }
        boolean manual = actorId != null;
        if (manual && !rateLimiter.tryAcquire(String.valueOf(actorId))) {
            throw new BusinessException(ErrorCode.AI_RATE_LIMIT_REACHED);
        }
        long started = System.nanoTime();
        // 聚合事实在预算/并发检查前产生：即使降级也保证报告内容完整。
        AiHealthReportFacts facts = aggregateFacts(reportDate);
        Integer errorCode = null;
        AiHealthReportSummary summary = null;
        boolean skipSummary = false;
        if (manual) {
            checkDailyTokenBudget(config);
            if (!permits.tryAcquire()) {
                throw new BusinessException(ErrorCode.AI_RATE_LIMIT_REACHED);
            }
        } else if (dailyBudgetExhausted(config)) {
            // 调度路径预算耗尽：跳过模型调用直接降级，预算约束不被绕过。
            skipSummary = true;
            errorCode = ErrorCode.AI_RATE_LIMIT_REACHED.getCode();
        }
        try {
            summary = skipSummary ? null : summarize(facts);
            if (summary == null) {
                errorCode = errorCode == null
                        ? Integer.valueOf(ErrorCode.AI_DISABLED_OR_REDACTION_FAILED.getCode()) : errorCode;
            }
        } catch (BusinessException exception) {
            errorCode = exception.getErrorCode().getCode();
            log.info("AI health report summary degraded, reportDate={}, code={}",
                    reportDate, errorCode);
        } catch (RuntimeException exception) {
            errorCode = ErrorCode.AI_PROVIDER_UNAVAILABLE.getCode();
            log.info("AI health report summary failed unexpectedly, reportDate={}", reportDate, exception);
        } finally {
            if (manual) {
                permits.release();
            }
        }
        AiHealthReportVo vo = buildVo(reportDate, facts, summary, errorCode,
                Duration.ofNanos(System.nanoTime() - started).toMillis());
        persist(vo);
        dispatchNotification(vo);
        log.info("AI health report generated, reportDate={}, status={}, durationMs={}",
                reportDate, vo.getStatus(), vo.getDurationMs());
        return vo;
    }

    /** 按 ID 查询报告；不存在返回 40404。 */
    public AiHealthReportVo get(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        AiHealthReportEntity entity = reportMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return toVo(entity);
    }

    /** 分页查询历史报告，按 report_date 倒序。 */
    public PageResult<AiHealthReportVo> list(Integer page, Integer pageSize) {
        if (page == null || page < 1 || pageSize == null || pageSize < 1 || pageSize > 100) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        Page<AiHealthReportEntity> pager = new Page<>(page, pageSize);
        List<AiHealthReportEntity> items = reportMapper.selectReports(pager);
        List<AiHealthReportVo> vos = new ArrayList<>(items.size());
        for (AiHealthReportEntity entity : items) {
            vos.add(toVo(entity));
        }
        PageResult<AiHealthReportVo> result = new PageResult<>();
        result.setItems(vos);
        result.setTotal(pager.getTotal());
        result.setPage(page);
        result.setPageSize(pageSize);
        return result;
    }

    /** 手动路径预算耗尽抛业务异常；调度路径由调用方按降级处理。 */
    private void checkDailyTokenBudget(AppProperties.Ai.Report config) {
        if (dailyBudgetExhausted(config)) {
            log.info("AI report daily token budget exhausted, budget={}", config.getDailyTokenBudget());
            throw new BusinessException(ErrorCode.AI_RATE_LIMIT_REACHED);
        }
    }

    /** 当日（UTC 对齐既有口径：服务器本地日）已落库报告 token 总和达到预算即视为耗尽；0 表示不限。 */
    private boolean dailyBudgetExhausted(AppProperties.Ai.Report config) {
        if (config.getDailyTokenBudget() <= 0) {
            return false;
        }
        LocalDate today = LocalDate.now(clock);
        LocalDateTime start = today.atStartOfDay();
        Long used = reportMapper.sumTotalTokensBetween(start, start.plusDays(1));
        return used != null && used >= config.getDailyTokenBudget();
    }

    /** 单次模型摘要：provider 缺席返回 null（两条路径统一降级），其余异常向上交由降级分支记录。 */
    private AiHealthReportSummary summarize(AiHealthReportFacts facts) {
        AiProvider provider = aiProvider.getIfAvailable();
        if (provider == null) {
            // ai.enabled=false 时 provider 不装配：报告链路降级出纯事实报告，不告警不重试。
            return null;
        }
        return provider.summarizeDailyHealth(facts);
    }

    /** 聚合报告窗口内的白名单事实：清单快照、指标均值/峰值、告警统计与离线清单。 */
    private AiHealthReportFacts aggregateFacts(LocalDate reportDate) {
        LocalDateTime start = reportDate.atStartOfDay();
        LocalDateTime end = start.plusDays(1);
        AiHealthReportFacts.ServerInventory inventory = buildInventory();
        AiHealthReportFacts.MetricPeaks peaks = buildMetricPeaks(start, end);
        AiHealthReportFacts.AlertStatistics statistics = buildAlertStatistics(start, end);
        List<AiHealthReportFacts.OfflineServer> offlineServers = new ArrayList<>();
        for (Map<String, Object> row : reportMapper.selectOfflineServers(MAX_LISTED_SERVERS)) {
            offlineServers.add(new AiHealthReportFacts.OfflineServer(
                    asLong(row.get("server_id")), asString(row.get("server_name")),
                    asString(row.get("agent_status")), asString(row.get("last_heartbeat_at"))));
        }
        return new AiHealthReportFacts(reportDate.toString(), inventory, peaks, statistics, offlineServers);
    }

    private AiHealthReportFacts.ServerInventory buildInventory() {
        Map<String, Object> row = reportMapper.selectServerInventory();
        int total = asInt(row.get("total_count"));
        int online = asInt(row.get("online_count"));
        double rate = total == 0 ? 0.0 : Math.round(online * 1000.0 / total) / 10.0;
        return new AiHealthReportFacts.ServerInventory(total, online, total - online, rate);
    }

    private AiHealthReportFacts.MetricPeaks buildMetricPeaks(LocalDateTime start, LocalDateTime end) {
        Map<String, Object> row = reportMapper.selectMetricAggregates(start, end);
        return new AiHealthReportFacts.MetricPeaks(
                asDouble(row.get("avg_cpu_percent")), asDouble(row.get("max_cpu_percent")),
                reportMapper.selectMaxCpuServerId(start, end),
                asDouble(row.get("avg_memory_percent")), asDouble(row.get("max_memory_percent")),
                reportMapper.selectMaxMemoryServerId(start, end),
                asDouble(row.get("avg_disk_percent")), asDouble(row.get("max_disk_percent")),
                reportMapper.selectMaxDiskServerId(start, end));
    }

    private AiHealthReportFacts.AlertStatistics buildAlertStatistics(LocalDateTime start, LocalDateTime end) {
        Map<String, Object> row = reportMapper.selectAlertStatistics(start, end);
        int total = asInt(row.get("total_triggered"));
        int resolved = asInt(row.get("resolved_count"));
        List<AiHealthReportFacts.TopAlertServer> topServers = new ArrayList<>();
        for (Map<String, Object> item : reportMapper.selectTopAlertServers(start, end, MAX_LISTED_SERVERS)) {
            topServers.add(new AiHealthReportFacts.TopAlertServer(
                    asLong(item.get("server_id")), asString(item.get("server_name")),
                    asInt(item.get("alert_count")), asInt(item.get("critical_count"))));
        }
        return new AiHealthReportFacts.AlertStatistics(total,
                asInt(row.get("critical_count")), asInt(row.get("warning_count")),
                resolved, total - resolved, topServers);
    }

    /** 组装报告 VO：succeeded 带摘要/关注点；degraded 仅事实并记录 error_code。 */
    private AiHealthReportVo buildVo(LocalDate reportDate, AiHealthReportFacts facts,
            AiHealthReportSummary summary, Integer errorCode, long durationMs) {
        AppProperties.Ai.Report config = appProperties.getAi().getReport();
        AiHealthReportVo vo = new AiHealthReportVo();
        vo.setReportDate(reportDate);
        vo.setStatus(summary == null ? STATUS_DEGRADED : STATUS_SUCCEEDED);
        vo.setProvider(appProperties.getAi().getProvider());
        vo.setModel(summary == null ? "" : appProperties.getAi().getModel());
        vo.setPromptVersion(config.getPromptVersion());
        vo.setSummary(summary == null ? null : summary.summary());
        vo.setTopConcerns(summary == null ? null : summary.topConcerns());
        vo.setLimitations(summary == null ? defaultLimitations(facts) : summary.limitations());
        vo.setFacts(objectMapper.convertValue(facts, Map.class));
        vo.setErrorCode(errorCode);
        vo.setUsage(summary == null ? new AiUsageVo() : summary.usage());
        vo.setDurationMs(durationMs);
        return vo;
    }

    /** 降级报告的固定 limitations：说明模型摘要缺席且离线清单为快照口径。 */
    private List<String> defaultLimitations(AiHealthReportFacts facts) {
        List<String> limitations = new ArrayList<>();
        limitations.add("Model summary unavailable; this report contains aggregated facts only.");
        limitations.add("Offline servers are a snapshot at generation time, not a disconnect event log.");
        if (facts != null && facts.offlineServers().size() >= MAX_LISTED_SERVERS) {
            limitations.add("Offline server list truncated to " + MAX_LISTED_SERVERS + " entries.");
        }
        return limitations;
    }

    /** 按自然日 UPSERT 落库；result_json 序列化失败不阻塞（summary 列保底）。 */
    private void persist(AiHealthReportVo vo) {
        AiHealthReportEntity entity = new AiHealthReportEntity();
        entity.setReportDate(vo.getReportDate());
        entity.setStatus(vo.getStatus());
        entity.setProvider(vo.getProvider());
        entity.setModel(vo.getModel());
        entity.setPromptVersion(vo.getPromptVersion());
        entity.setSummary(vo.getSummary());
        entity.setErrorCode(vo.getErrorCode());
        entity.setDurationMs(vo.getDurationMs());
        entity.setCreatedAt(LocalDateTime.now(clock));
        if (vo.getUsage() != null) {
            entity.setInputTokens(vo.getUsage().getInputTokens());
            entity.setOutputTokens(vo.getUsage().getOutputTokens());
            entity.setTotalTokens(vo.getUsage().getTotalTokens());
        }
        try {
            entity.setResultJson(objectMapper.writeValueAsString(vo));
        } catch (JsonProcessingException exception) {
            log.warn("AI health report resultJson serialization failed, reportDate={}", vo.getReportDate());
            entity.setResultJson(null);
        }
        if (reportMapper.upsertReport(entity) != 1) {
            throw new BusinessException(ErrorCode.DATABASE_ERROR);
        }
    }

    /** 尽力而为通知：通知器缺席或实现自身异常都只记日志，绝不拖垮生成主链路。 */
    private void dispatchNotification(AiHealthReportVo vo) {
        AiHealthReportNotifier notification = notifier.getIfAvailable();
        if (notification == null) {
            return;
        }
        try {
            notification.dispatch(vo);
        } catch (RuntimeException exception) {
            log.warn("AI health report notification failed unexpectedly, reportDate={}",
                    vo.getReportDate(), exception);
        }
    }

    /** 实体 → VO：优先读 result_json，损坏时退回结构化列保证回看可用。 */
    @SuppressWarnings("unchecked")
    private AiHealthReportVo toVo(AiHealthReportEntity entity) {
        if (entity.getResultJson() != null) {
            try {
                return objectMapper.readValue(entity.getResultJson(), AiHealthReportVo.class);
            } catch (JsonProcessingException exception) {
                log.warn("AI health report resultJson parse failed, id={}", entity.getId());
            }
        }
        AiHealthReportVo vo = new AiHealthReportVo();
        vo.setId(entity.getId());
        vo.setReportDate(entity.getReportDate());
        vo.setStatus(entity.getStatus());
        vo.setProvider(entity.getProvider());
        vo.setModel(entity.getModel());
        vo.setPromptVersion(entity.getPromptVersion());
        vo.setSummary(entity.getSummary());
        vo.setErrorCode(entity.getErrorCode());
        vo.setDurationMs(entity.getDurationMs());
        AiUsageVo usage = new AiUsageVo();
        if (entity.getTotalTokens() != null) {
            usage.setInputTokens(entity.getInputTokens() == null ? 0 : entity.getInputTokens());
            usage.setOutputTokens(entity.getOutputTokens() == null ? 0 : entity.getOutputTokens());
            usage.setTotalTokens(entity.getTotalTokens());
        }
        vo.setUsage(usage);
        return vo;
    }

    private Long asLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private int asInt(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private Double asDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
