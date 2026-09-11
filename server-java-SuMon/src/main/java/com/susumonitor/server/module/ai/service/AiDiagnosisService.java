package com.susumonitor.server.module.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.common.vo.PageResult;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.ai.entity.AiDiagnosticRunEntity;
import com.susumonitor.server.module.ai.limit.AiDiagnosisRateLimiter;
import com.susumonitor.server.module.ai.mapper.AiDiagnosticRunMapper;
import com.susumonitor.server.module.ai.model.AiDiagnosisContext;
import com.susumonitor.server.module.ai.provider.AiProviderResolver;
import com.susumonitor.server.module.ai.vo.AiDiagnosisVo;
import com.susumonitor.server.module.ai.vo.AiEvidenceVo;
import com.susumonitor.server.module.ai.vo.AiFindingVo;
import com.susumonitor.server.module.ai.vo.AiUsageVo;
import com.susumonitor.server.module.alert.service.AlertRecordService;
import com.susumonitor.server.module.alert.vo.AlertRecordVo;
import com.susumonitor.server.module.metrics.service.MetricsService;
import com.susumonitor.server.module.metrics.vo.MetricsHistoryVo;
import com.susumonitor.server.module.metrics.vo.MetricsLatestVo;
import com.susumonitor.server.module.server.service.ServerService;
import com.susumonitor.server.module.server.vo.ServerStatusVo;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/** 编排管理员只读诊断，负责权限后的白名单上下文、provider 调用和最小审计。 */
@Slf4j
@Service
@ConditionalOnProperty(name = "susumonitor.ai.enabled", havingValue = "true")
public class AiDiagnosisService {
    private static final DateTimeFormatter UTC_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private final ServerService serverService;
    private final MetricsService metricsService;
    private final AlertRecordService alertRecordService;
    private final AiProviderResolver providerResolver;
    private final AiDiagnosisRateLimiter rateLimiter;
    private final AiDiagnosticRunMapper auditMapper;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Semaphore permits;

    /** 注入只读业务契约、按用户 provider 解析器、限流器与审计组件；不接触任何凭据或终端服务。 */
    public AiDiagnosisService(ServerService serverService, MetricsService metricsService,
            AlertRecordService alertRecordService, AiProviderResolver providerResolver,
            AiDiagnosisRateLimiter rateLimiter,
            AiDiagnosticRunMapper auditMapper, AppProperties appProperties,
            ObjectMapper objectMapper, Clock clock) {
        this.serverService = serverService;
        this.metricsService = metricsService;
        this.alertRecordService = alertRecordService;
        this.providerResolver = providerResolver;
        this.rateLimiter = rateLimiter;
        this.auditMapper = auditMapper;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.permits = new Semaphore(appProperties.getAi().getMaxConcurrentRequests());
    }

    /** 生成一次结构化诊断；provider 失败时返回确定性只读摘要。 */
    public AiDiagnosisVo diagnose(Long actorId, Long serverId, String question, Integer historyMinutes) {
        AppProperties.Ai ai = appProperties.getAi();
        // kill switch 在任何参数或数据库访问前短路：禁用时必须零触达，不查询服务器也不调用 provider。
        if (!ai.isEnabled()) {
            throw new BusinessException(ErrorCode.AI_DISABLED_OR_REDACTION_FAILED);
        }
        validateRequest(ai, serverId, question, historyMinutes);
        // 按管理员固定窗口限流：检查即计数，超限抛 42906（不占并发许可、不调 provider）。
        rateLimiter.checkAllowed(actorId);
        // 按天 token 预算：当日 completed 调用总量达到上限后拒绝新请求，防止成本失控。
        checkDailyTokenBudget(ai);
        if (!permits.tryAcquire()) {
            throw new BusinessException(ErrorCode.AI_RATE_LIMIT_REACHED);
        }
        long started = System.nanoTime();
        AiDiagnosticRunEntity audit = null;
        try {
            // 按调用者解析生效配置（个人优先，全局兜底）；无可用配置时 fail-closed 50304。
            AiProviderResolver.Resolution resolution = providerResolver.resolveForActor(actorId);
            AiDiagnosisContext context = buildContext(serverId, historyMinutes);
            String contextHash = sha256(objectMapper.writeValueAsString(context));
            audit = beginAudit(actorId, serverId, resolution, contextHash);
            try {
                AiDiagnosisVo result = resolution.provider().diagnose(question, context);
                validateResult(result);
                result.setProvider(resolution.providerName());
                result.setModel(resolution.model());
                result.setPromptVersion(ai.getPromptVersion());
                result.setModelUsed(true);
                completeAudit(audit, result, elapsed(started));
                return result;
            } catch (BusinessException exception) {
                failAudit(audit, exception.getErrorCode(), elapsed(started));
                if (exception.getErrorCode() == ErrorCode.AI_PROVIDER_UNAVAILABLE
                        || exception.getErrorCode() == ErrorCode.AI_PROVIDER_TIMEOUT
                        || exception.getErrorCode() == ErrorCode.AI_RESPONSE_INVALID
                        || exception.getErrorCode() == ErrorCode.AI_RATE_LIMIT_REACHED) {
                    return fallback(context, resolution, exception.getErrorCode());
                }
                throw exception;
            }
        } catch (JsonProcessingException exception) {
            if (audit != null) {
                failAudit(audit, ErrorCode.AI_DISABLED_OR_REDACTION_FAILED, elapsed(started));
            }
            throw new BusinessException(ErrorCode.AI_DISABLED_OR_REDACTION_FAILED, exception);
        } finally {
            permits.release();
        }
    }

    private AiDiagnosisContext buildContext(Long serverId, int historyMinutes) {
        ServerStatusVo status = serverService.status(serverId);
        MetricsLatestVo latest = null;
        try {
            latest = metricsService.latest(serverId);
        } catch (BusinessException exception) {
            if (exception.getErrorCode() != ErrorCode.RESOURCE_NOT_FOUND) {
                throw exception;
            }
        }
        List<AiEvidenceVo> evidence = new ArrayList<>();
        if (latest != null) {
            addMetricEvidence(evidence, "cpu_percent", latest.getCpuPercent(), latest.getCollectedAt());
            addMetricEvidence(evidence, "memory_percent", latest.getMemoryPercent(), latest.getCollectedAt());
            addMetricEvidence(evidence, "disk_percent", latest.getDiskPercent(), latest.getCollectedAt());
            addMetricEvidence(evidence, "load_avg", latest.getLoadAvg(), latest.getCollectedAt());
            addMetricEvidence(evidence, "temperature", latest.getTemperature(), latest.getCollectedAt());
        }
        if (historyMinutes > 0) {
            OffsetDateTime end = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
            OffsetDateTime start = end.minusMinutes(historyMinutes);
            PageResult<MetricsHistoryVo> history = metricsService.history(serverId, start, end, 1, 100);
            for (MetricsHistoryVo item : history.getItems()) {
                addMetricEvidence(evidence, "cpu_percent", item.getCpuPercent(), item.getCollectedAt());
                addMetricEvidence(evidence, "memory_percent", item.getMemoryPercent(), item.getCollectedAt());
                addMetricEvidence(evidence, "load_avg", item.getLoadAvg(), item.getCollectedAt());
            }
        }
        PageResult<AlertRecordVo> alerts = alertRecordService.listRecords(serverId, null, 1, 20);
        List<AiDiagnosisContext.AlertSummary> summaries = alerts.getItems().stream()
                .map(this::toAlertSummary).toList();
        return new AiDiagnosisContext(serverId, status.getStatus(), status.getAgentStatus(), historyMinutes,
                evidence, summaries);
    }

    private AiDiagnosisContext.AlertSummary toAlertSummary(AlertRecordVo alert) {
        return new AiDiagnosisContext.AlertSummary(alert.getId(), safe(alert.getMetric()), safe(alert.getStatus()),
                safe(alert.getLevel()), alert.getCurrentValue(), alert.getThresholdValue(),
                alert.getTriggeredAt() == null ? null : alert.getTriggeredAt().format(UTC_FORMAT));
    }

    private void addMetricEvidence(List<AiEvidenceVo> evidence, String metric, Object value, OffsetDateTime at) {
        if (value == null || at == null || evidence.size() >= 50) return;
        AiEvidenceVo item = new AiEvidenceVo();
        item.setMetric(metric);
        item.setValue(value);
        // 服务端组装的 ISO-8601 UTC 字符串；响应契约 observed_at 保持 date-time 语义。
        item.setObservedAt(at.withOffsetSameInstant(ZoneOffset.UTC).format(UTC_FORMAT));
        item.setSource("monitoring_summary");
        evidence.add(item);
    }

    private AiDiagnosisVo fallback(AiDiagnosisContext context, AiProviderResolver.Resolution resolution,
            ErrorCode reason) {
        AiDiagnosisVo result = new AiDiagnosisVo();
        result.setSummary("Deterministic monitoring summary returned because the AI provider was unavailable.");
        result.setSeverity(context.alertSummaries().stream().anyMatch(item -> "critical".equals(item.level()))
                ? "critical" : "info");
        AiFindingVo finding = new AiFindingVo();
        finding.setTitle("Provider fallback");
        finding.setDescription("Review the supplied monitoring evidence and active alert state using approved procedures.");
        finding.setConfidence("high");
        result.setFindings(List.of(finding));
        result.setEvidence(context.evidence());
        result.setRecommendations(List.of("Use the existing read-only metrics and alert views for further investigation."));
        result.setLimitations(List.of("No model output was used; no remediation or command was executed."));
        result.setModelUsed(false);
        result.setProvider(resolution.providerName());
        result.setModel(resolution.model() == null ? "" : resolution.model());
        result.setPromptVersion(appProperties.getAi().getPromptVersion());
        result.setUsage(new AiUsageVo());
        log.info("AI diagnosis fell back to deterministic summary, reason={}", reason.getCode());
        return result;
    }

    private AiDiagnosticRunEntity beginAudit(Long actorId, Long serverId,
            AiProviderResolver.Resolution resolution, String hash) {
        AiDiagnosticRunEntity audit = new AiDiagnosticRunEntity();
        audit.setRequestId(MDC.get("request_id"));
        audit.setCorrelationId(MDC.get("correlation_id"));
        audit.setActorId(actorId);
        audit.setServerId(serverId);
        audit.setPromptVersion(appProperties.getAi().getPromptVersion());
        audit.setProvider(resolution.providerName());
        audit.setModel(resolution.model());
        audit.setStatus("running");
        audit.setContextHash(hash);
        audit.setCreatedAt(java.time.LocalDateTime.now(clock));
        if (auditMapper.insertRun(audit) != 1) throw new BusinessException(ErrorCode.DATABASE_ERROR);
        return audit;
    }

    private void completeAudit(AiDiagnosticRunEntity audit, AiDiagnosisVo result, long duration) {
        audit.setStatus("completed"); audit.setDurationMs(duration); audit.setCompletedAt(java.time.LocalDateTime.now(clock));
        if (result.getUsage() != null) {
            audit.setInputTokens(result.getUsage().getInputTokens()); audit.setOutputTokens(result.getUsage().getOutputTokens());
            audit.setTotalTokens(result.getUsage().getTotalTokens());
        }
        try { audit.setResultJson(objectMapper.writeValueAsString(result)); }
        catch (JsonProcessingException exception) { audit.setResultJson(null); }
        auditMapper.completeRun(audit);
    }

    private void failAudit(AiDiagnosticRunEntity audit, ErrorCode code, long duration) {
        audit.setStatus("failed"); audit.setErrorCode(code.getCode()); audit.setDurationMs(duration);
        audit.setCompletedAt(java.time.LocalDateTime.now(clock)); auditMapper.failRun(audit);
    }

    /**
     * 校验当日（UTC）token 预算；预算为 0 表示不限。
     *
     * <p>以 completed 调用的 total_tokens 聚合为口径，命中 created_at 索引，
     * 管理诊断频次下为毫秒级查询；达到上限即拒绝，不预估本次消耗。</p>
     */
    private void checkDailyTokenBudget(AppProperties.Ai ai) {
        if (ai.getDailyTokenBudget() <= 0) {
            return;
        }
        java.time.LocalDate today = java.time.LocalDate.now(clock);
        java.time.LocalDateTime start = today.atStartOfDay();
        Long used = auditMapper.selectTotalTokensBetween(start, start.plusDays(1));
        if (used != null && used >= ai.getDailyTokenBudget()) {
            log.info("AI daily token budget exhausted, usedTokens={}, budget={}", used, ai.getDailyTokenBudget());
            throw new BusinessException(ErrorCode.AI_RATE_LIMIT_REACHED);
        }
    }

    private void validateRequest(AppProperties.Ai ai, Long serverId, String question, Integer historyMinutes) {        if (serverId == null || serverId <= 0 || question == null || question.isBlank()
                || question.length() > ai.getMaxQuestionLength() || historyMinutes == null
                || historyMinutes < 0 || historyMinutes > ai.getMaxHistoryMinutes()
                || containsSensitiveOrActionRequest(question)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        if (!serverService.existsActive(serverId)) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    private void validateResult(AiDiagnosisVo result) {
        if (result == null || result.getSummary() == null || result.getSummary().isBlank()
                || result.getSummary().length() > 4000 || result.getFindings() == null || result.getEvidence() == null
                || result.getRecommendations() == null || result.getLimitations() == null
                || result.getRecommendations().stream().anyMatch(this::containsActionInstruction)
                || result.getLimitations().stream().anyMatch(item -> item != null && item.length() > 2000)) {
            throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID);
        }
    }

    private boolean containsSensitiveOrActionRequest(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("terminal") || normalized.contains("ssh") || normalized.contains("password")
                || normalized.contains("private key") || normalized.contains("token")
                || normalized.contains("execute command") || normalized.contains("run command")
                || normalized.contains("sudo") || normalized.contains("curl ") || normalized.contains("http://")
                || normalized.contains("https://");
    }

    private boolean containsActionInstruction(String value) {
        if (value == null) return true;
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("terminal") || normalized.contains("ssh") || normalized.contains("sudo")
                || normalized.contains("execute") || normalized.contains("run command")
                || normalized.contains("http://") || normalized.contains("https://")
                || normalized.contains("curl ") || normalized.contains("wget ");
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private long elapsed(long started) { return java.time.Duration.ofNanos(System.nanoTime() - started).toMillis(); }
    private String safe(String value) { return value == null ? "unknown" : value; }
}
