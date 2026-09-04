package com.susumonitor.server.module.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.ai.entity.AiAlertExplanationEntity;
import com.susumonitor.server.module.ai.mapper.AiAlertExplanationMapper;
import com.susumonitor.server.module.ai.model.AiAlertFacts;
import com.susumonitor.server.module.ai.model.AiDiagnosisContext;
import com.susumonitor.server.module.ai.provider.AiProvider;
import com.susumonitor.server.module.ai.vo.AiAlertExplanationVo;
import com.susumonitor.server.module.ai.vo.AiEvidenceVo;
import com.susumonitor.server.module.metrics.service.MetricsService;
import com.susumonitor.server.module.metrics.vo.MetricsLatestVo;
import com.susumonitor.server.module.server.service.ServerService;
import com.susumonitor.server.module.server.vo.ServerStatusVo;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 编排 AI 告警智能解释：白名单上下文构建、provider 调用、内容校验与解释存储。
 *
 * <p>本服务随 {@code susumonitor.ai.explanation.enabled} 装配，由事件消费侧调用：
 * {@link #explain} 在事务外执行（LLM 调用耗时，不得拉长数据库事务），
 * {@link #save} 在消费事务内与幂等记录同事务落库。解释失败不阻塞告警主链路——
 * 异常向上抛出由消费者按重试/DLQ 语义处理。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "susumonitor.ai.explanation.enabled", havingValue = "true")
public class AiAlertExplanationService {

    /** 解释 prompt 版本（服务端代码常量，与诊断/命令域版本相互独立）。 */
    public static final String PROMPT_VERSION = "ai-alert-explanation-v1";

    private static final DateTimeFormatter UTC_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final ObjectProvider<AiProvider> aiProvider;
    private final AiAlertExplanationMapper explanationMapper;
    private final ServerService serverService;
    private final MetricsService metricsService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** 注入模型端口（可缺席）、解释存储与只读取数契约；不接触任何凭据或终端服务。 */
    @Autowired
    public AiAlertExplanationService(ObjectProvider<AiProvider> aiProvider,
            AiAlertExplanationMapper explanationMapper, ServerService serverService,
            MetricsService metricsService, AppProperties appProperties,
            ObjectMapper objectMapper, Clock clock) {
        this.aiProvider = aiProvider;
        this.explanationMapper = explanationMapper;
        this.serverService = serverService;
        this.metricsService = metricsService;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 生成一次告警解释：开关短路 → provider 可用性 → 当日预算 → 白名单上下文 →
     * provider 调用 → 内容复核。该方法不含数据库写操作。
     *
     * @param facts 告警触发事实（来自事件载荷白名单）
     * @return 已填充 provider/model/prompt_version/usage 的解释结果
     * @throws BusinessException provider 缺席/禁用（50304）、预算耗尽（42906）、
     *         服务器不存在（40401）、响应不合规（50305）
     */
    public AiAlertExplanationVo explain(AiAlertFacts facts) {
        AppProperties.Ai.Explanation config = appProperties.getAi().getExplanation();
        // kill switch 短路：Bean 装配已由开关决定，此处防御运行期配置误用。
        if (!config.isEnabled()) {
            throw new BusinessException(ErrorCode.AI_DISABLED_OR_REDACTION_FAILED);
        }
        // ai.enabled=false 时 OpenAiCompatibleProvider 不装配：解释链路 fail-closed，
        // 异常使事件进入重试/DLQ，配置错误可见且修复后可重放。
        AiProvider provider = aiProvider.getIfAvailable();
        if (provider == null) {
            throw new BusinessException(ErrorCode.AI_DISABLED_OR_REDACTION_FAILED);
        }
        checkDailyTokenBudget(config);
        AiDiagnosisContext context = buildContext(facts.serverId());
        long started = System.nanoTime();
        AiAlertExplanationVo result = provider.explainAlert(facts, context);
        validateContent(result);
        result.setProvider(appProperties.getAi().getProvider());
        result.setModel(appProperties.getAi().getModel());
        result.setPromptVersion(PROMPT_VERSION);
        result.setRecordId(facts.recordId());
        log.info("AI alert explanation generated, recordId={}, durationMs={}",
                facts.recordId(), Duration.ofNanos(System.nanoTime() - started).toMillis());
        return result;
    }

    /**
     * 将解释结果落库（消费事务内调用，与消费幂等记录同生共死）。
     *
     * @param eventId 触发事件 ID（审计溯源）
     * @param facts 告警触发事实
     * @param result explain 返回的解释结果
     * @param durationMs provider 调用与校验总耗时
     */
    public void save(String eventId, AiAlertFacts facts, AiAlertExplanationVo result, long durationMs) {
        AiAlertExplanationEntity entity = new AiAlertExplanationEntity();
        entity.setAlertRecordId(facts.recordId());
        entity.setServerId(facts.serverId());
        entity.setRuleId(facts.ruleId());
        entity.setEventId(eventId);
        entity.setPromptVersion(result.getPromptVersion());
        entity.setProvider(result.getProvider());
        entity.setModel(result.getModel() == null ? "" : result.getModel());
        entity.setSummary(result.getSummary());
        entity.setCreatedAt(LocalDateTime.now(clock));
        entity.setDurationMs(durationMs);
        if (result.getUsage() != null) {
            entity.setInputTokens(result.getUsage().getInputTokens());
            entity.setOutputTokens(result.getUsage().getOutputTokens());
            entity.setTotalTokens(result.getUsage().getTotalTokens());
        }
        try {
            entity.setResultJson(objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException exception) {
            // 序列化失败不阻塞落库：summary 列已保底，result_json 置空。
            log.warn("AI alert explanation resultJson serialization failed, recordId={}", facts.recordId());
            entity.setResultJson(null);
        }
        if (explanationMapper.insert(entity) != 1) {
            throw new BusinessException(ErrorCode.DATABASE_ERROR);
        }
    }

    /** 按告警记录 ID 查询已落库解释；无解释返回 null，由调用方决定 404 语义。 */
    public AiAlertExplanationVo getByRecordId(Long alertRecordId) {
        AiAlertExplanationEntity entity = explanationMapper.selectByRecordId(alertRecordId);
        if (entity == null) {
            return null;
        }
        if (entity.getResultJson() != null) {
            try {
                return objectMapper.readValue(entity.getResultJson(), AiAlertExplanationVo.class);
            } catch (JsonProcessingException exception) {
                log.warn("AI alert explanation resultJson parse failed, recordId={}", alertRecordId);
            }
        }
        // result_json 缺失或损坏时退回结构化列，保证回看接口仍可给出最小结果。
        AiAlertExplanationVo vo = new AiAlertExplanationVo();
        vo.setRecordId(entity.getAlertRecordId());
        vo.setSummary(entity.getSummary());
        vo.setProvider(entity.getProvider());
        vo.setModel(entity.getModel());
        vo.setPromptVersion(entity.getPromptVersion());
        return vo;
    }

    /**
     * 构建解释用白名单上下文：服务器状态 + 最新指标证据，不含历史明细与告警摘要
     * （触发事实已由 facts 携带，控制事件驱动路径的 token 成本）。
     */
    private AiDiagnosisContext buildContext(Long serverId) {
        ServerStatusVo status = serverService.status(serverId);
        List<AiEvidenceVo> evidence = new ArrayList<>();
        try {
            MetricsLatestVo latest = metricsService.latest(serverId);
            addMetricEvidence(evidence, "cpu_percent", latest.getCpuPercent(), latest.getCollectedAt());
            addMetricEvidence(evidence, "memory_percent", latest.getMemoryPercent(), latest.getCollectedAt());
            addMetricEvidence(evidence, "disk_percent", latest.getDiskPercent(), latest.getCollectedAt());
            addMetricEvidence(evidence, "load_avg", latest.getLoadAvg(), latest.getCollectedAt());
            addMetricEvidence(evidence, "temperature", latest.getTemperature(), latest.getCollectedAt());
        } catch (BusinessException exception) {
            if (exception.getErrorCode() != ErrorCode.RESOURCE_NOT_FOUND) {
                throw exception;
            }
            // 指标缺失不阻塞解释：服务器存在但尚无上报数据是合法状态。
        }
        return new AiDiagnosisContext(serverId, status.getStatus(), status.getAgentStatus(), 0,
                evidence, List.of());
    }

    private void addMetricEvidence(List<AiEvidenceVo> evidence, String metric, Object value, OffsetDateTime at) {
        if (value == null || at == null || evidence.size() >= 50) return;
        AiEvidenceVo item = new AiEvidenceVo();
        item.setMetric(metric);
        item.setValue(value);
        item.setObservedAt(at.withOffsetSameInstant(ZoneOffset.UTC).format(UTC_FORMAT));
        item.setSource("monitoring_summary");
        evidence.add(item);
    }

    /** 当日（UTC）已落库解释 token 总和达到预算上限即拒绝；0 表示不限。 */
    private void checkDailyTokenBudget(AppProperties.Ai.Explanation config) {
        if (config.getDailyTokenBudget() <= 0) {
            return;
        }
        LocalDate today = LocalDate.now(clock);
        LocalDateTime start = today.atStartOfDay();
        Long used = explanationMapper.sumTotalTokensBetween(start, start.plusDays(1));
        if (used != null && used >= config.getDailyTokenBudget()) {
            log.info("AI explanation daily token budget exhausted, usedTokens={}, budget={}",
                    used, config.getDailyTokenBudget());
            throw new BusinessException(ErrorCode.AI_RATE_LIMIT_REACHED);
        }
    }

    /**
     * 内容复核：与诊断同口径的反执行指令检查——建议/原因/影响列表中出现
     * 可执行指令字样即整体拒绝（响应进入重试/DLQ，不落库不推送）。
     */
    private void validateContent(AiAlertExplanationVo result) {
        if (containsActionInstruction(result.getSummary())
                || !withoutActionInstruction(result.getPossibleCauses())
                || !withoutActionInstruction(result.getImpact())
                || !withoutActionInstruction(result.getSuggestions())
                || !withoutActionInstruction(result.getLimitations())) {
            throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID);
        }
    }

    private boolean withoutActionInstruction(List<String> values) {
        if (values == null) {
            return false;
        }
        return values.stream().allMatch(item -> !containsActionInstruction(item));
    }

    private boolean containsActionInstruction(String value) {
        if (value == null) return true;
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("terminal") || normalized.contains("ssh") || normalized.contains("sudo")
                || normalized.contains("execute") || normalized.contains("run command")
                || normalized.contains("http://") || normalized.contains("https://")
                || normalized.contains("curl ") || normalized.contains("wget ");
    }
}
