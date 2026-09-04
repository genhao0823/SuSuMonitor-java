package com.susumonitor.server.module.ai.controller;

import com.susumonitor.server.common.ApiResponse;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.module.ai.service.AiAlertExplanationService;
import com.susumonitor.server.module.ai.vo.AiAlertExplanationVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供管理员专用的 AI 告警解释回看端点（F1）。
 *
 * <p>随 {@code susumonitor.ai.explanation.enabled} 开关装配：关闭时本 Controller
 * 不创建，请求按未知路径返回 404，与命令域开关行为一致。</p>
 */
@Tag(name = "ai-alert-explanation", description = "AI alert explanation review")
@RestController
@RequestMapping("/api/alerts/records")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "susumonitor.ai.explanation.enabled", havingValue = "true")
public class AiAlertExplanationController {

    private final AiAlertExplanationService explanationService;

    /** 返回一条告警记录的 AI 智能解释；解释不存在时返回 404。 */
    @Operation(summary = "Get the AI explanation of an alert record",
            description = "Admin-only stored explanation view; 404 when the alert record has no AI explanation.",
            operationId = "getAlertExplanation", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/{id}/explanation")
    public ApiResponse<AiAlertExplanationVo> getExplanation(@PathVariable("id") Long recordId) {
        AiAlertExplanationVo explanation = explanationService.getByRecordId(recordId);
        if (explanation == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return ApiResponse.success(explanation);
    }
}
