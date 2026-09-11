package com.susumonitor.server.module.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.common.vo.PageResult;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.ai.entity.AiQaRunEntity;
import com.susumonitor.server.module.ai.limit.AiQaRateLimiter;
import com.susumonitor.server.module.ai.mapper.AiQaRunMapper;
import com.susumonitor.server.module.ai.provider.AiProviderResolver;
import com.susumonitor.server.module.ai.provider.AiQaAnswer;
import com.susumonitor.server.module.ai.provider.OpenAiCompatibleProvider;
import com.susumonitor.server.module.ai.provider.SpringAiChatClient;
import com.susumonitor.server.module.ai.tools.AiQaToolContext;
import com.susumonitor.server.module.ai.vo.AiQaVo;
import com.susumonitor.server.module.ai.vo.AiToolCallVo;
import com.susumonitor.server.module.ai.vo.AiUsageVo;
import com.susumonitor.server.module.alert.service.AlertRecordService;
import com.susumonitor.server.module.alert.vo.AlertRecordVo;
import com.susumonitor.server.module.metrics.service.MetricsService;
import com.susumonitor.server.module.metrics.vo.MetricsLatestVo;
import com.susumonitor.server.module.server.service.ServerService;
import com.susumonitor.server.module.server.vo.ServerStatusVo;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 编排管理员运维问答（F2）：工具化问答（Spring AI）失败时回退无工具单次调用，
 * 再失败返回确定性监控事实摘要；全程最小审计与独立限流/预算。
 *
 * <p>降级语义与只读诊断一致：provider 不可用/超时/响应非法/限流一律返回
 * HTTP 200 的降级结果（{@code model_used=false / degraded=true}），原始错误码
 * 记录在 {@code ai_qa_runs.error_code}，不透传客户端。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "susumonitor.ai.enabled", havingValue = "true")
public class AiQaService {
    private final ServerService serverService;
    private final MetricsService metricsService;
    private final AlertRecordService alertRecordService;
    private final AiProviderResolver providerResolver;
    private final AiQaRateLimiter rateLimiter;
    private final AiQaRunMapper auditMapper;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Semaphore permits;

    /** 注入只读业务契约、按用户 provider 解析器与审计组件。 */
    public AiQaService(ServerService serverService, MetricsService metricsService,
            AlertRecordService alertRecordService, AiProviderResolver providerResolver,
            AiQaRateLimiter rateLimiter,
            AiQaRunMapper auditMapper, AppProperties appProperties, ObjectMapper objectMapper, Clock clock) {
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

    /** 回答一次运维问答；工具版 → 无工具版 → 确定性摘要逐级降级。 */
    public AiQaVo ask(Long actorId, Long serverId, String question) {
        AppProperties.Ai ai = appProperties.getAi();
        // qa kill switch 在任何参数或数据库访问前短路：禁用时必须零触达。
        if (!ai.getQa().isEnabled()) {
            throw new BusinessException(ErrorCode.AI_DISABLED_OR_REDACTION_FAILED);
        }
        validateRequest(ai.getQa(), serverId, question);
        rateLimiter.checkAllowed(actorId);
        checkDailyTokenBudget(ai.getQa());
        if (!permits.tryAcquire()) {
            throw new BusinessException(ErrorCode.AI_RATE_LIMIT_REACHED);
        }
        long started = System.nanoTime();
        AiQaRunEntity audit = null;
        try {
            // 按调用者解析生效配置（个人优先，全局兜底）；无可用配置时 fail-closed 50304。
            AiProviderResolver.QaResolution resolution = providerResolver.resolveQaForActor(actorId);
            audit = beginAudit(actorId, serverId, resolution, sha256(question));
            AiQaVo result = askWithFallbackChain(actorId, serverId, question, ai, resolution);
            completeAudit(audit, result, elapsed(started));
            return result;
        } finally {
            permits.release();
        }
    }

    /** 降级链：Spring AI 工具版（装配时）→ 无工具单次调用 → 确定性监控摘要。 */
    private AiQaVo askWithFallbackChain(Long actorId, Long serverId, String question,
            AppProperties.Ai ai, AiProviderResolver.QaResolution resolution) {
        SpringAiChatClient client = resolution.toolClient();
        if (client != null) {
            AiQaToolContext.open(actorId, ai.getQa().getMaxToolIterations());
            try {
                AiQaAnswer answer = client.ask(question);
                List<AiToolCallVo> toolCalls = currentCalls();
                AiQaVo result = toVo(answer, toolCalls, resolution, ai);
                result.setDegraded(false);
                return result;
            } catch (BusinessException exception) {
                log.info("AI QA tool path failed, code={}, falling back to no-tools mode",
                        exception.getErrorCode().getCode());
                List<AiToolCallVo> toolCalls = currentCalls();
                AiQaVo noTools = askNoTools(question, serverId, resolution, toolCalls);
                if (noTools != null) {
                    return noTools;
                }
                return deterministicFallback(serverId, ai, resolution, toolCalls,
                        exception.getErrorCode());
            } catch (RuntimeException exception) {
                log.info("AI QA tool path failed unexpectedly, falling back to no-tools mode", exception);
                List<AiToolCallVo> toolCalls = currentCalls();
                AiQaVo noTools = askNoTools(question, serverId, resolution, toolCalls);
                if (noTools != null) {
                    return noTools;
                }
                return deterministicFallback(serverId, ai, resolution, toolCalls,
                        ErrorCode.AI_PROVIDER_UNAVAILABLE);
            } finally {
                AiQaToolContext.close();
            }
        }
        AiQaVo noTools = askNoTools(question, serverId, resolution, List.of());
        if (noTools != null) {
            return noTools;
        }
        return deterministicFallback(serverId, ai, resolution, List.of(), ErrorCode.AI_PROVIDER_UNAVAILABLE);
    }

    /** 无工具降级：预聚合白名单上下文后单次调用；失败返回 null 交给确定性降级。 */
    private AiQaVo askNoTools(String question, Long serverId,
            AiProviderResolver.QaResolution resolution, List<AiToolCallVo> toolCalls) {
        try {
            String contextJson = objectMapper.writeValueAsString(buildContext(serverId));
            AiQaAnswer answer = resolution.noToolsProvider().askWithoutTools(question, contextJson);
            AiQaVo result = toVo(answer, toolCalls, resolution, appProperties.getAi());
            result.setDegraded(true);
            return result;
        } catch (BusinessException | JsonProcessingException exception) {
            log.info("AI QA no-tools fallback failed, using deterministic summary");
            return null;
        }
    }

    private List<AiToolCallVo> currentCalls() {
        AiQaToolContext context = AiQaToolContext.current();
        return context == null ? List.of() : context.calls();
    }

    /** 组装无工具路径的白名单上下文：单机聚合状态/最新指标/近期告警；全局问题仅给说明。 */
    private Map<String, Object> buildContext(Long serverId) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (serverId == null) {
            context.put("scope", "global");
            context.put("note", "No per-server context is attached for global questions; "
                    + "state clearly what cannot be determined.");
            return context;
        }
        ServerStatusVo status = serverService.status(serverId);
        context.put("server_status", status);
        try {
            MetricsLatestVo latest = metricsService.latest(serverId);
            context.put("latest_metrics", latest);
        } catch (BusinessException exception) {
            if (exception.getErrorCode() != ErrorCode.RESOURCE_NOT_FOUND) {
                throw exception;
            }
        }
        PageResult<AlertRecordVo> alerts = alertRecordService.listRecords(serverId, null, 1, 5);
        context.put("recent_alerts", alerts.getItems());
        return context;
    }

    private AiQaVo toVo(AiQaAnswer answer, List<AiToolCallVo> toolCalls,
            AiProviderResolver.QaResolution resolution, AppProperties.Ai ai) {
        AiQaVo result = new AiQaVo();
        result.setAnswer(answer.answer());
        result.setToolCalls(toolCalls);
        result.setModelUsed(true);
        result.setProvider(resolution.providerName());
        result.setModel(resolution.model());
        result.setPromptVersion(ai.getQa().getPromptVersion());
        result.setUsage(answer.usage());
        return result;
    }

    /** 确定性降级：不调模型，返回服务端聚合的监控事实摘要（model_used=false）。 */
    private AiQaVo deterministicFallback(Long serverId, AppProperties.Ai ai,
            AiProviderResolver.QaResolution resolution, List<AiToolCallVo> toolCalls, ErrorCode reason) {
        AiQaVo result = new AiQaVo();
        result.setToolCalls(toolCalls);
        result.setModelUsed(false);
        result.setDegraded(true);
        result.setProvider(resolution.providerName());
        result.setModel(resolution.model() == null ? "" : resolution.model());
        result.setPromptVersion(ai.getQa().getPromptVersion());
        result.setUsage(new AiUsageVo());
        result.setAnswer(buildDeterministicAnswer(serverId));
        log.info("AI QA fell back to deterministic summary, reason={}", reason.getCode());
        return result;
    }

    private String buildDeterministicAnswer(Long serverId) {
        try {
            if (serverId == null) {
                return "模型暂不可用（确定性降级）。请稍后重试，或在控制台查看服务器列表与告警页面。";
            }
            StringBuilder answer = new StringBuilder("模型暂不可用（确定性降级），以下为服务端聚合事实：\n");
            ServerStatusVo status = serverService.status(serverId);
            answer.append("- 服务器状态：").append(status.getStatus())
                    .append("；Agent：").append(status.getAgentStatus()).append('\n');
            try {
                MetricsLatestVo latest = metricsService.latest(serverId);
                answer.append("- 最新指标：cpu=").append(latest.getCpuPercent())
                        .append("%, memory=").append(latest.getMemoryPercent())
                        .append("%, disk=").append(latest.getDiskPercent()).append("%\n");
            } catch (BusinessException exception) {
                answer.append("- 最新指标：暂无数据\n");
            }
            PageResult<AlertRecordVo> alerts = alertRecordService.listRecords(serverId, null, 1, 5);
            answer.append("- 近期告警 ").append(alerts.getTotal()).append(" 条（最新 ")
                    .append(alerts.getItems().size()).append(" 条见告警页面）");
            return answer.toString();
        } catch (RuntimeException exception) {
            return "模型暂不可用（确定性降级）。请稍后重试，或在控制台查看服务器与告警页面。";
        }
    }

    private AiQaRunEntity beginAudit(Long actorId, Long serverId,
            AiProviderResolver.QaResolution resolution, String questionHash) {
        AiQaRunEntity audit = new AiQaRunEntity();
        audit.setRequestId(MDC.get("request_id"));
        audit.setCorrelationId(MDC.get("correlation_id"));
        audit.setActorId(actorId);
        audit.setServerId(serverId);
        audit.setPromptVersion(appProperties.getAi().getQa().getPromptVersion());
        audit.setProvider(resolution.providerName());
        audit.setModel(resolution.model());
        audit.setStatus("running");
        audit.setQuestionHash(questionHash);
        audit.setCreatedAt(java.time.LocalDateTime.now(clock));
        if (auditMapper.insertRun(audit) != 1) throw new BusinessException(ErrorCode.DATABASE_ERROR);
        return audit;
    }

    private void completeAudit(AiQaRunEntity audit, AiQaVo result, long duration) {
        audit.setStatus("completed"); audit.setDurationMs(duration);
        audit.setCompletedAt(java.time.LocalDateTime.now(clock));
        if (result.getUsage() != null) {
            audit.setInputTokens(result.getUsage().getInputTokens());
            audit.setOutputTokens(result.getUsage().getOutputTokens());
            audit.setTotalTokens(result.getUsage().getTotalTokens());
        }
        try {
            audit.setToolCallsJson(objectMapper.writeValueAsString(result.getToolCalls()));
            audit.setResultJson(objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException exception) {
            audit.setToolCallsJson(null);
            audit.setResultJson(null);
        }
        auditMapper.completeRun(audit);
    }

    private void failAudit(AiQaRunEntity audit, ErrorCode code, long duration) {
        audit.setStatus("failed"); audit.setErrorCode(code.getCode()); audit.setDurationMs(duration);
        audit.setCompletedAt(java.time.LocalDateTime.now(clock)); auditMapper.failRun(audit);
    }

    /** 校验当日（UTC）token 预算；预算为 0 表示不限（口径与诊断预算一致，独立计数）。 */
    private void checkDailyTokenBudget(AppProperties.Ai.Qa qa) {
        if (qa.getDailyTokenBudget() <= 0) {
            return;
        }
        java.time.LocalDate today = java.time.LocalDate.now(clock);
        java.time.LocalDateTime start = today.atStartOfDay();
        Long used = auditMapper.selectTotalTokensBetween(start, start.plusDays(1));
        if (used != null && used >= qa.getDailyTokenBudget()) {
            log.info("AI QA daily token budget exhausted, usedTokens={}, budget={}",
                    used, qa.getDailyTokenBudget());
            throw new BusinessException(ErrorCode.AI_RATE_LIMIT_REACHED);
        }
    }

    private void validateRequest(AppProperties.Ai.Qa qa, Long serverId, String question) {
        if (question == null || question.isBlank() || question.length() > qa.getMaxQuestionLength()
                || containsSensitiveRequest(question)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        if (serverId != null && serverId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        if (serverId != null && !serverService.existsActive(serverId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    /** 敏感词 fail-closed：问答禁止请求终端/SSH/凭据/外链类内容（命令提案仅经白名单工具）。 */
    private boolean containsSensitiveRequest(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("terminal") || normalized.contains("ssh") || normalized.contains("password")
                || normalized.contains("private key") || normalized.contains("token")
                || normalized.contains("sudo") || normalized.contains("curl ") || normalized.contains("wget ")
                || normalized.contains("http://") || normalized.contains("https://");
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

    private long elapsed(long started) {
        return java.time.Duration.ofNanos(System.nanoTime() - started).toMillis();
    }
}
