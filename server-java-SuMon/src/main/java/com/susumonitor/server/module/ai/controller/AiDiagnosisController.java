package com.susumonitor.server.module.ai.controller;

import com.susumonitor.server.common.ApiResponse;
import com.susumonitor.server.module.ai.dto.AiDiagnosisRequest;
import com.susumonitor.server.module.ai.service.AiDiagnosisService;
import com.susumonitor.server.module.ai.vo.AiDiagnosisVo;
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

/** 提供管理员专用的只读 AI 诊断入口，不暴露任何执行工具。 */
@Tag(name = "ai-diagnosis", description = "Structured read-only monitoring diagnosis")
@RestController
@RequestMapping("/api/ai/diagnoses")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "susumonitor.ai.enabled", havingValue = "true")
public class AiDiagnosisController {

    private final AiDiagnosisService aiDiagnosisService;

    /** 根据白名单监控事实生成结构化诊断。 */
    @Operation(summary = "Generate a read-only AI diagnosis",
            description = "Admin-only advisory analysis; no terminal, SSH, command, or write operation is available.",
            operationId = "createAiDiagnosis", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping
    public ApiResponse<AiDiagnosisVo> createDiagnosis(
            @Valid @RequestBody AiDiagnosisRequest request,
            @AuthenticationPrincipal AuthenticatedUser operator) {
        return ApiResponse.success(aiDiagnosisService.diagnose(operator.id(), request.getServerId(),
                request.getQuestion(), request.getHistoryMinutes()));
    }
}
