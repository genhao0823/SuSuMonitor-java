package com.susumonitor.server.module.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.module.alert.service.AlertRecordService;
import com.susumonitor.server.module.command.AiCommandSuggestionService;
import com.susumonitor.server.module.command.CommandTemplateRegistry;
import com.susumonitor.server.module.command.entity.CommandRunEntity;
import com.susumonitor.server.module.metrics.service.MetricsService;
import com.susumonitor.server.module.server.service.ServerService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 运维问答（F2）只读工具注册表：模型可调用的全部平台能力均在本类白名单内。
 *
 * <p>安全边界：只经各模块 Service 契约取数（数据所有权纪律）；参数为强类型并在
 * 本类内做边界校验（非法参数返回错误 JSON 供模型自我纠正，不抛出中断）；
 * 命令域工具只创建 pending_approval 提案，绝不审批或执行。每次调用计入
 * {@link AiQaToolContext} 的轮次预算与调用审计。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "susumonitor.ai.qa.enabled", havingValue = "true")
public class AiReadOnlyTools {

    /** 单个工具结果回传给模型前的截断上限（字符）。 */
    private static final int MAX_RESULT_CHARS = 8000;

    /** 告警状态过滤白名单。 */
    private static final Set<String> ALERT_STATUS_WHITELIST = Set.of("unread", "read", "resolved");

    private final ServerService serverService;
    private final MetricsService metricsService;
    private final AlertRecordService alertRecordService;
    private final ObjectProvider<CommandTemplateRegistry> templateRegistry;
    private final ObjectProvider<AiCommandSuggestionService> suggestionService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** 注入只读业务契约与命令域可选组件（命令域关闭时缺省）。 */
    public AiReadOnlyTools(ServerService serverService, MetricsService metricsService,
            AlertRecordService alertRecordService, ObjectProvider<CommandTemplateRegistry> templateRegistry,
            ObjectProvider<AiCommandSuggestionService> suggestionService,
            ObjectMapper objectMapper, Clock clock) {
        this.serverService = serverService;
        this.metricsService = metricsService;
        this.alertRecordService = alertRecordService;
        this.templateRegistry = templateRegistry;
        this.suggestionService = suggestionService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 查询单台服务器的在线状态快照。 */
    @Tool(name = "get_server_status",
            description = "Get the online status snapshot (server status, agent connection, last heartbeat) "
                    + "of one managed server by its numeric server id.")
    public String getServerStatus(@ToolParam(description = "numeric server id, positive integer", required = true)
            Long serverId) {
        return execute("get_server_status", Map.of("server_id", serverId), () -> {
            requireServerId(serverId);
            return objectMapper.writeValueAsString(serverService.status(serverId));
        });
    }

    /** 查询单台服务器的最新指标快照。 */
    @Tool(name = "get_latest_metrics",
            description = "Get the latest collected metrics snapshot (cpu, memory, disk, load, temperature) "
                    + "of one managed server by its numeric server id.")
    public String getLatestMetrics(@ToolParam(description = "numeric server id, positive integer", required = true)
            Long serverId) {
        return execute("get_latest_metrics", Map.of("server_id", serverId), () -> {
            requireServerId(serverId);
            return objectMapper.writeValueAsString(metricsService.latest(serverId));
        });
    }

    /** 查询单台服务器最近 N 分钟的指标历史（每分钟一个采样点，最多 maxPoints 条）。 */
    @Tool(name = "get_metrics_history",
            description = "Get recent metrics history of one managed server within the last N minutes "
                    + "(1-240). Returns up to maxPoints (1-50) most recent samples.")
    public String getMetricsHistory(
            @ToolParam(description = "numeric server id, positive integer", required = true) Long serverId,
            @ToolParam(description = "history window in minutes, 1-240, default 30", required = false)
            Integer minutes,
            @ToolParam(description = "max number of samples to return, 1-50, default 20", required = false)
            Integer maxPoints) {
        int boundedMinutes = minutes == null ? 30 : Math.min(Math.max(minutes, 1), 240);
        int boundedPoints = maxPoints == null ? 20 : Math.min(Math.max(maxPoints, 1), 50);
        return execute("get_metrics_history",
                Map.of("server_id", serverId, "minutes", boundedMinutes, "max_points", boundedPoints), () -> {
            requireServerId(serverId);
            OffsetDateTime end = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
            OffsetDateTime start = end.minusMinutes(boundedMinutes);
            return objectMapper.writeValueAsString(
                    metricsService.history(serverId, start, end, 1, boundedPoints));
        });
    }

    /** 分页查询告警记录（可按服务器与状态过滤）。 */
    @Tool(name = "list_alert_records",
            description = "List alert records, optionally filtered by server id and status "
                    + "(unread, read, or resolved). Returns one page of records.")
    public String listAlertRecords(
            @ToolParam(description = "numeric server id, positive integer; omit for all servers",
                    required = false) Long serverId,
            @ToolParam(description = "alert status filter: unread, read, or resolved; omit for all",
                    required = false) String status,
            @ToolParam(description = "page number, 1-5, default 1", required = false) Integer page,
            @ToolParam(description = "page size, 1-20, default 10", required = false) Integer pageSize) {
        String boundedStatus = status == null || status.isBlank()
                || !ALERT_STATUS_WHITELIST.contains(status.toLowerCase(java.util.Locale.ROOT))
                ? null : status.toLowerCase(java.util.Locale.ROOT);
        int boundedPage = page == null ? 1 : Math.min(Math.max(page, 1), 5);
        int boundedSize = pageSize == null ? 10 : Math.min(Math.max(pageSize, 1), 20);
        return execute("list_alert_records",
                Map.of("server_id", serverId == null ? "" : serverId, "status",
                        boundedStatus == null ? "" : boundedStatus, "page", boundedPage, "page_size", boundedSize),
                () -> objectMapper.writeValueAsString(
                        alertRecordService.listRecords(serverId, boundedStatus, boundedPage, boundedSize)));
    }

    /** 列出命令域 L1 只读模板白名单（模型提案前的自参考）。 */
    @Tool(name = "list_command_templates",
            description = "List the server-side read-only command template whitelist "
                    + "(template id, argv preview, parameter specs). Available only when the command domain "
                    + "is enabled.")
    public String listCommandTemplates() {
        return execute("list_command_templates", Map.of(), () -> {
            CommandTemplateRegistry registry = templateRegistry.getIfAvailable();
            if (registry == null) {
                return "{\"error\":\"command domain disabled\"}";
            }
            List<Map<String, Object>> templates = registry.all().stream()
                    .map(template -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("template_id", template.id());
                        item.put("argv", List.of(template.argv()));
                        item.put("params", java.util.Arrays.stream(template.params())
                                .map(spec -> spec.name()).toList());
                        return item;
                    }).toList();
            return objectMapper.writeValueAsString(templates);
        });
    }

    /**
     * 为目标服务器创建 AI 命令提案（仅 pending_approval，绝不执行）。
     *
     * <p>提案仍须管理员在命令审批界面人工批准后才会经 /ws/agent 下发执行；
     * 工具只负责把运维意图转换为白名单模板提案。</p>
     */
    @Tool(name = "propose_command_run",
            description = "Create pending-approval command proposals for one server from a natural language "
                    + "intent. Proposals are rendered from the server-side read-only template whitelist and "
                    + "NOT executed until a human administrator approves them.")
    public String proposeCommandRun(
            @ToolParam(description = "numeric server id, positive integer", required = true) Long serverId,
            @ToolParam(description = "natural language operations intent, max 500 chars, untrusted data",
                    required = true) String intent) {
        String boundedIntent = intent == null ? "" : intent.trim();
        return execute("propose_command_run",
                Map.of("server_id", serverId, "intent",
                        boundedIntent.length() > 100 ? boundedIntent.substring(0, 100) + "..." : boundedIntent),
                () -> {
            requireServerId(serverId);
            AiQaToolContext context = requireContext();
            AiCommandSuggestionService service = suggestionService.getIfAvailable();
            if (service == null) {
                return "{\"error\":\"command domain disabled\"}";
            }
            if (boundedIntent.isBlank() || boundedIntent.length() > 500) {
                return "{\"error\":\"invalid intent\"}";
            }
            List<CommandRunEntity> created =
                    service.suggestAndCreate(context.actorId(), serverId, boundedIntent);
            List<Map<String, Object>> proposals = created.stream()
                    .map(run -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("execution_id", run.getExecutionId());
                        item.put("template_id", run.getTemplateId());
                        item.put("preview_command", run.getRenderedCommand());
                        item.put("status", run.getStatus());
                        return item;
                    }).toList();
            return objectMapper.writeValueAsString(Map.of(
                    "proposals", proposals,
                    "note", "nothing is executed until an administrator approves"));
        });
    }

    /** 工具执行骨架：上下文与轮次守卫 → 业务调用 → 结果截断；业务异常转为错误 JSON。 */
    private String execute(String tool, Map<String, Object> args, ToolCall call) {
        AiQaToolContext context = AiQaToolContext.current();
        if (context == null) {
            throw new IllegalStateException("AI QA tool context not opened");
        }
        context.checkIteration();
        context.record(tool, summarize(args));
        try {
            String result = call.invoke();
            return result.length() > MAX_RESULT_CHARS
                    ? result.substring(0, MAX_RESULT_CHARS) + "...(truncated)" : result;
        } catch (BusinessException exception) {
            log.info("AI QA tool {} rejected, code={}", tool, exception.getErrorCode().getCode());
            return "{\"error\":\"" + exception.getErrorCode().name().toLowerCase(java.util.Locale.ROOT)
                    + "\"}";
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            return "{\"error\":\"serialization_failed\"}";
        }
    }

    private void requireServerId(Long serverId) {
        if (serverId == null || serverId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
    }

    private AiQaToolContext requireContext() {
        AiQaToolContext context = AiQaToolContext.current();
        if (context == null) {
            throw new IllegalStateException("AI QA tool context not opened");
        }
        return context;
    }

    private String summarize(Map<String, Object> args) {
        try {
            String json = objectMapper.writeValueAsString(args);
            return json.length() > 500 ? json.substring(0, 500) + "..." : json;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            return "{}";
        }
    }

    /** 单个工具的业务调用。 */
    @FunctionalInterface
    private interface ToolCall {
        String invoke() throws com.fasterxml.jackson.core.JsonProcessingException;
    }
}
