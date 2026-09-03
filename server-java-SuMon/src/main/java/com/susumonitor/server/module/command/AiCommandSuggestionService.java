package com.susumonitor.server.module.command;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.ai.model.AiDiagnosisContext;
import com.susumonitor.server.module.ai.provider.AiProvider;
import com.susumonitor.server.module.ai.provider.CommandSuggestion;
import com.susumonitor.server.module.ai.vo.AiEvidenceVo;
import com.susumonitor.server.module.alert.service.AlertRecordService;
import com.susumonitor.server.module.command.entity.CommandRunEntity;
import com.susumonitor.server.module.metrics.service.MetricsService;
import com.susumonitor.server.module.metrics.vo.MetricsLatestVo;
import com.susumonitor.server.module.server.service.ServerService;
import com.susumonitor.server.module.server.vo.ServerStatusVo;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * AI 命令建议编排：意图 fail-closed 校验 → 白名单上下文组装 → provider 建议 →
 * 模板注册表逐条校验 → 创建待审批运行。
 *
 * <p>需要 AI 诊断开关（provider Bean 存在）；命令域开关由本模块条件装配保证。
 * 单条建议非法（模板未知/参数不合法）时丢弃该条并记日志，不影响其余建议。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "susumonitor.ai.command.enabled", havingValue = "true")
public class AiCommandSuggestionService {

    private static final DateTimeFormatter UTC_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final ObjectProvider<AiProvider> aiProvider;
    private final CommandRunService commandRunService;
    private final CommandTemplateRegistry templateRegistry;
    private final ServerService serverService;
    private final MetricsService metricsService;
    private final AlertRecordService alertRecordService;
    private final AppProperties appProperties;
    private final Clock clock;

    /** 注入 provider（可缺省）、运行服务、模板注册表与只读上下文来源。 */
    public AiCommandSuggestionService(ObjectProvider<AiProvider> aiProvider,
            CommandRunService commandRunService, CommandTemplateRegistry templateRegistry,
            ServerService serverService, MetricsService metricsService,
            AlertRecordService alertRecordService, AppProperties appProperties, Clock clock) {
        this.aiProvider = aiProvider;
        this.commandRunService = commandRunService;
        this.templateRegistry = templateRegistry;
        this.serverService = serverService;
        this.metricsService = metricsService;
        this.alertRecordService = alertRecordService;
        this.appProperties = appProperties;
        this.clock = clock;
    }

    /**
     * 生成建议并创建待审批运行；返回成功入库的建议运行列表（可能为空）。
     *
     * @throws BusinessException 50304（AI 未启用）、40002（意图非法/服务器不存在）、
     *                          provider 失败按原错误码透传（无降级建议）
     */
    public List<CommandRunEntity> suggestAndCreate(Long proposerId, Long serverId, String intent) {
        if (!appProperties.getAi().isEnabled()) {
            throw new BusinessException(ErrorCode.AI_DISABLED_OR_REDACTION_FAILED);
        }
        AiProvider provider = aiProvider.getIfAvailable();
        if (provider == null) {
            throw new BusinessException(ErrorCode.AI_DISABLED_OR_REDACTION_FAILED);
        }
        validateIntent(intent, serverId);
        List<String> whitelist = templateRegistry.all().stream()
                .map(CommandTemplateRegistry.Template::id).toList();
        List<CommandSuggestion> suggestions =
                provider.suggestCommands(intent, buildContext(serverId), whitelist);
        List<CommandRunEntity> created = new ArrayList<>();
        for (CommandSuggestion suggestion : suggestions) {
            try {
                // 模型可能附带模板未声明的多余键（实测会塞 server_id 等）；
                // 仅保留注册表声明的参数键——渲染防线不变（未声明键本就不进 argv）。
                Map<String, String> filtered =
                        filterDeclaredParams(suggestion.templateId(), suggestion.params());
                String proposalJson = buildProposalJson(suggestion);
                created.add(commandRunService.createPendingRun(proposerId, serverId,
                        suggestion.templateId(), filtered,
                        CommandRunService.SOURCE_AI, proposalJson));
            } catch (BusinessException exception) {
                // 单条建议非法（模板未知/参数不合法/限流）丢弃该条，不阻断其余建议。
                log.warn("command suggestion dropped, template={}, reason={}",
                        suggestion.templateId(), exception.getErrorCode().getCode());
            }
        }
        return created;
    }

    /** 按模板声明过滤参数键；模板未知时返回原样（交由创建路径统一拒绝）。 */
    private Map<String, String> filterDeclaredParams(String templateId, Map<String, String> params) {
        CommandTemplateRegistry.Template template = templateRegistry.find(templateId);
        if (template == null || template.params().length == 0) {
            return Map.of();
        }
        Map<String, String> filtered = new LinkedHashMap<>();
        for (CommandTemplateRegistry.ParamSpec spec : template.params()) {
            String value = params == null ? null : params.get(spec.name());
            if (value != null) {
                filtered.put(spec.name(), value);
            }
        }
        return filtered;
    }

    /** 意图 fail-closed 校验：长度、服务器归属与敏感/执行关键词拒绝。 */
    private void validateIntent(String intent, Long serverId) {
        if (intent == null || intent.isBlank() || intent.length() > 2000
                || serverId == null || serverId <= 0 || !serverService.existsActive(serverId)
                || containsSensitiveOrActionRequest(intent)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
    }

    /** 组装最小白名单上下文（状态 + 最新指标 + 近期告警摘要），与只读诊断同口径。 */
    private AiDiagnosisContext buildContext(Long serverId) {
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
            addEvidence(evidence, "cpu_percent", latest.getCpuPercent(), latest.getCollectedAt());
            addEvidence(evidence, "memory_percent", latest.getMemoryPercent(), latest.getCollectedAt());
            addEvidence(evidence, "disk_percent", latest.getDiskPercent(), latest.getCollectedAt());
            addEvidence(evidence, "load_avg", latest.getLoadAvg(), latest.getCollectedAt());
        }
        List<AiDiagnosisContext.AlertSummary> alerts = alertRecordService
                .listRecords(serverId, null, 1, 10).getItems().stream()
                .map(alert -> new AiDiagnosisContext.AlertSummary(alert.getId(),
                        alert.getMetric() == null ? "unknown" : alert.getMetric(),
                        alert.getStatus() == null ? "unknown" : alert.getStatus(),
                        alert.getLevel() == null ? "unknown" : alert.getLevel(),
                        alert.getCurrentValue(), alert.getThresholdValue(),
                        alert.getTriggeredAt() == null ? null
                                : alert.getTriggeredAt().format(UTC_FORMAT)))
                .toList();
        return new AiDiagnosisContext(serverId, status.getStatus(), status.getAgentStatus(), 0,
                evidence, alerts);
    }

    private void addEvidence(List<AiEvidenceVo> evidence, String metric, Object value, OffsetDateTime at) {
        if (value == null || at == null || evidence.size() >= 50) {
            return;
        }
        AiEvidenceVo item = new AiEvidenceVo();
        item.setMetric(metric);
        item.setValue(value);
        item.setObservedAt(at.withOffsetSameInstant(ZoneOffset.UTC).format(UTC_FORMAT));
        item.setSource("monitoring_summary");
        evidence.add(item);
    }

    /** 建议元数据 JSON（落审计行 proposal_json）。 */
    private String buildProposalJson(CommandSuggestion suggestion) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("reason", suggestion.reason());
        node.put("model", appProperties.getAi().getModel());
        node.put("prompt_version", appProperties.getAi().getPromptVersion() + "-command");
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            return null;
        }
    }

    /** 敏感/执行关键词拒绝（与只读诊断同一 fail-closed 思路）。 */
    private boolean containsSensitiveOrActionRequest(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("terminal") || normalized.contains("ssh ")
                || normalized.contains("password") || normalized.contains("private key")
                || normalized.contains("token") || normalized.contains("curl ")
                || normalized.contains("http://") || normalized.contains("https://")
                || normalized.contains("rm -rf");
    }
}
