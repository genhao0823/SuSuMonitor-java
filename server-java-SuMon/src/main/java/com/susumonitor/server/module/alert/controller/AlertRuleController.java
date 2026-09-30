package com.susumonitor.server.module.alert.controller;

import com.susumonitor.server.module.alert.dto.CreateAlertRuleRequest;
import com.susumonitor.server.module.alert.dto.UpdateAlertRuleRequest;
import com.susumonitor.server.module.alert.service.AlertRuleService;
import com.susumonitor.server.module.alert.vo.AlertRuleVo;
import com.susumonitor.server.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 告警规则 REST API，提供规则创建、查询、更新和删除。
 *
 * <p>创建、更新和删除需要 admin 角色，查询需要已认证用户。</p>
 */
@Tag(name = "alert", description = "Alert rules, records, and push")
@RestController
@RequestMapping("/api/alerts/rules")
@Validated
@RequiredArgsConstructor
public class AlertRuleController {

    private final AlertRuleService alertRuleService;

    /** 创建告警规则，仅 admin。 */
    @Operation(
            summary = "Create alert rule",
            description = "Creates an alert rule. Admin-only. server_id null means a general rule "
                    + "matching all servers. metric must be cpu/memory/disk/temperature/load; the load "
                    + "rule evaluates the system load average exposed by metrics.load_avg. operator "
                    + "must be >/>=/</<=. level must be warning/critical.",
            operationId = "createAlertRule",
            security = @SecurityRequirement(name = "bearerAuth"))
    // 声明创建接口的错误响应（HTTP 状态 + 业务错误码），错误码以契约 README 全表为准。
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rule created"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameter (40002)"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired JWT (40100)"),
            @ApiResponse(responseCode = "403", description = "Authenticated but not an administrator (40300)"),
            @ApiResponse(responseCode = "404", description = "Resource not found (40400)"),
            @ApiResponse(responseCode = "409", description = "Resource state conflict (40900)")
    })
    @PostMapping
    public com.susumonitor.server.common.ApiResponse<AlertRuleVo> createRule(
            @Valid
            @RequestBody CreateAlertRuleRequest request,
            @AuthenticationPrincipal AuthenticatedUser operator) {
        return com.susumonitor.server.common.ApiResponse.success(alertRuleService.createRule(request, operator.id()));
    }

    /** 查询所有未删除规则，已认证用户可查；通知渠道详情（Webhook URL 等）仅 admin 可见。 */
    @Operation(
            summary = "List alert rules",
            description = "Returns all non-deleted alert rules, ordered by created_at DESC. "
                    + "Authenticated users only. Notification channel details "
                    + "(notify_email/notify_dingtalk/notify_webhook) are only returned to admins.",
            operationId = "listAlertRules",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rule list"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired JWT (40100)")
    })
    @GetMapping
    public com.susumonitor.server.common.ApiResponse<List<AlertRuleVo>> listRules(
            @AuthenticationPrincipal AuthenticatedUser user) {
        // Webhook URL 可能内嵌 access_token 等密钥，非 admin 一律脱敏（与前端路由 requiresAdmin 对齐）。
        boolean exposeNotify = "admin".equals(user.role());
        return com.susumonitor.server.common.ApiResponse.success(alertRuleService.listRules(exposeNotify));
    }

    /** 更新规则，仅 admin。 */
    @Operation(
            summary = "Update alert rule",
            description = "Updates threshold_value, level, and enabled. Does not allow changing "
                    + "metric, operator, or server_id. Admin-only.",
            operationId = "updateAlertRule",
            security = @SecurityRequirement(name = "bearerAuth"))
    // 声明更新接口的错误响应（HTTP 状态 + 业务错误码），错误码以契约 README 全表为准。
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rule updated"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameter (40002)"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired JWT (40100)"),
            @ApiResponse(responseCode = "403", description = "Authenticated but not an administrator (40300)"),
            @ApiResponse(responseCode = "404", description = "Resource not found (40400)"),
            @ApiResponse(responseCode = "409", description = "Resource state conflict (40900)")
    })
    @PutMapping("/{id}")
    public com.susumonitor.server.common.ApiResponse<AlertRuleVo> updateRule(
            @Parameter(description = "Rule ID, must be positive.")
            @PathVariable("id")
            @Positive Long ruleId,
            @Valid @RequestBody UpdateAlertRuleRequest request) {
        return com.susumonitor.server.common.ApiResponse.success(alertRuleService.updateRule(ruleId, request));
    }

    /** 软删除规则，仅 admin。 */
    @Operation(
            summary = "Delete alert rule (soft delete)",
            description = "Soft-deletes the rule by setting deleted=1. Admin-only.",
            operationId = "deleteAlertRule",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rule deleted"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired JWT (40100)"),
            @ApiResponse(responseCode = "403", description = "Authenticated but not an administrator (40300)"),
            @ApiResponse(responseCode = "404", description = "Resource not found (40400)")
    })
    @DeleteMapping("/{id}")
    public com.susumonitor.server.common.ApiResponse<Void> deleteRule(
            @Parameter(description = "Rule ID, must be positive.")
            @PathVariable("id")
            @Positive Long ruleId) {
        alertRuleService.deleteRule(ruleId);
        return com.susumonitor.server.common.ApiResponse.success(null);
    }
}
