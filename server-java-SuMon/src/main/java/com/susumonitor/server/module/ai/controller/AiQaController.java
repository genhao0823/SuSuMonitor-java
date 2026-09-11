package com.susumonitor.server.module.ai.controller;

import com.susumonitor.server.common.ApiResponse;
import com.susumonitor.server.module.ai.dto.AiQaRequest;
import com.susumonitor.server.module.ai.service.AiQaService;
import com.susumonitor.server.module.ai.vo.AiQaVo;
import com.susumonitor.server.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供管理员专用的运维问答入口；模型仅能调用注册表内的只读工具。 */
@Tag(name = "ai-qa", description = "Tool-assisted read-only operations Q&A for administrators")
@RestController
@RequestMapping("/api/ai/qa")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "susumonitor.ai.enabled", havingValue = "true")
public class AiQaController {

    private final AiQaService aiQaService;

    /** 回答一次运维问答；server_id 可空表示全局性问题。 */
    @Operation(summary = "Ask an operations question with read-only tools",
            description = "Admin-only Q&A. The model may only call platform-registered read-only tools "
                    + "(metrics, alerts, server status, template whitelist, pending-approval command proposals); "
                    + "no terminal, SSH, or write operation is available.",
            operationId = "askAiQuestion", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping
    public ApiResponse<AiQaVo> ask(
            @Valid @RequestBody AiQaRequest request,
            @AuthenticationPrincipal AuthenticatedUser operator) {
        return ApiResponse.success(aiQaService.ask(operator.id(), request.getServerId(),
                request.getQuestion()));
    }
}
