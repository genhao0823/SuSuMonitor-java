package com.susumonitor.server.module.command.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.common.ApiResponse;
import com.susumonitor.server.common.vo.PageResult;
import com.susumonitor.server.module.command.AiCommandSuggestionService;
import com.susumonitor.server.module.command.CommandRunService;
import com.susumonitor.server.module.command.CommandRunVos;
import com.susumonitor.server.module.command.CommandTemplateRegistry;
import com.susumonitor.server.module.command.dto.CommandSuggestionRequest;
import com.susumonitor.server.module.command.dto.ManualCommandRequest;
import com.susumonitor.server.module.command.entity.CommandRunEntity;
import com.susumonitor.server.module.command.vo.CommandRunVo;
import com.susumonitor.server.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 命令域 M1（审批制）REST 端点：admin 专属；模板白名单 + 审批状态机 + 审计。
 */
@Tag(name = "ai-command", description = "Approval-based AI command domain (M1)")
@RestController
@RequestMapping("/api/ai/commands")
@RequiredArgsConstructor
@Validated
@ConditionalOnProperty(name = "susumonitor.ai.command.enabled", havingValue = "true")
public class CommandRunController {

    private final CommandRunService commandRunService;
    private final CommandTemplateRegistry templateRegistry;
    private final AiCommandSuggestionService suggestionService;
    private final ObjectMapper objectMapper;

    /** AI 根据意图生成建议并逐条创建待审批运行（返回可能为空列表）。 */
    @Operation(summary = "Suggest commands via AI and create pending runs",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/suggestions")
    public ApiResponse<List<CommandRunVo>> suggest(
            @Valid @RequestBody CommandSuggestionRequest request,
            @AuthenticationPrincipal AuthenticatedUser operator) {
        List<CommandRunVo> runs = new ArrayList<>();
        for (CommandRunEntity run : suggestionService.suggestAndCreate(operator.id(),
                request.getServerId(), request.getIntent())) {
            runs.add(CommandRunVos.toVo(run, objectMapper));
        }
        return ApiResponse.success(runs);
    }

    /** 手动选择模板创建待审批运行（链路验证与人工发起）。 */
    @Operation(summary = "Create a pending command run manually",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/runs")
    public ApiResponse<CommandRunVo> createManual(
            @Valid @RequestBody ManualCommandRequest request,
            @AuthenticationPrincipal AuthenticatedUser operator) {
        CommandRunEntity run = commandRunService.createPendingRun(operator.id(),
                request.getServerId(), request.getTemplateId(), request.getParams(),
                CommandRunService.SOURCE_MANUAL, null);
        return ApiResponse.success(CommandRunVos.toVo(run, objectMapper));
    }

    /** 审批并立即下发执行；返回最新状态，结果经 GET 轮询获取。 */
    @Operation(summary = "Approve a pending run and dispatch it to the agent",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/runs/{id}/approve")
    public ApiResponse<CommandRunVo> approve(
            @PathVariable("id") @Positive Long runId,
            @AuthenticationPrincipal AuthenticatedUser operator) {
        return ApiResponse.success(CommandRunVos.toVo(
                commandRunService.approve(runId, operator.id()), objectMapper));
    }

    /** 拒绝待审批运行。 */
    @Operation(summary = "Reject a pending run",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/runs/{id}/reject")
    public ApiResponse<CommandRunVo> reject(
            @PathVariable("id") @Positive Long runId,
            @AuthenticationPrincipal AuthenticatedUser operator) {
        return ApiResponse.success(CommandRunVos.toVo(
                commandRunService.reject(runId, operator.id()), objectMapper));
    }

    /** 查询单条运行记录（含脱敏截断后的执行结果）。 */
    @Operation(summary = "Get a command run", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/runs/{id}")
    public ApiResponse<CommandRunVo> get(@PathVariable("id") @Positive Long runId) {
        return ApiResponse.success(CommandRunVos.toVo(commandRunService.get(runId), objectMapper));
    }

    /** 分页查询运行记录（可按服务器/状态过滤）。 */
    @Operation(summary = "List command runs", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/runs")
    public ApiResponse<PageResult<CommandRunVo>> list(
            @RequestParam(value = "server_id", required = false) Long serverId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") @Min(1) Integer page,
            @RequestParam(value = "page_size", defaultValue = "20") @Min(1) @Max(100) Integer pageSize) {
        PageResult<CommandRunEntity> pageResult = commandRunService.list(serverId, status, page, pageSize);
        PageResult<CommandRunVo> result = new PageResult<>();
        result.setTotal(pageResult.getTotal());
        result.setPage(pageResult.getPage());
        result.setPageSize(pageResult.getPageSize());
        List<CommandRunVo> items = new ArrayList<>();
        for (CommandRunEntity entity : pageResult.getItems()) {
            items.add(CommandRunVos.toVo(entity, objectMapper));
        }
        result.setItems(items);
        return ApiResponse.success(result);
    }

    /** 列出白名单模板（id/argv/参数正则），契约 command-protocol-v1.md 的 Java 镜像。 */
    @Operation(summary = "List whitelisted command templates",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/templates")
    public ApiResponse<List<Map<String, Object>>> templates() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (CommandTemplateRegistry.Template template : templateRegistry.all()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", template.id());
            item.put("argv", template.argv());
            List<Map<String, String>> params = new ArrayList<>();
            for (CommandTemplateRegistry.ParamSpec spec : template.params()) {
                params.add(Map.of("name", spec.name(), "pattern", spec.pattern().pattern()));
            }
            item.put("params", params);
            result.add(item);
        }
        return ApiResponse.success(result);
    }
}
