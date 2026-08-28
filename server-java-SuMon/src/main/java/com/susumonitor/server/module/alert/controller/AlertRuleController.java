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
// 将告警规则端点归入 OpenAPI 文档的 alert 分组，与 openapi-alert.json 契约的 tag 一致。
@Tag(name = "alert", description = "Alert rules, records, and push")
// 将当前类注册为 REST Controller，并将返回值写入 HTTP 响应体。
@RestController
// 为告警规则接口统一增加 /api/alerts/rules 路径前缀。
@RequestMapping("/api/alerts/rules")
// 启用方法参数约束，使非正数 ID 返回参数错误。
@Validated
// 自动生成包含 final 字段的构造方法，用于构造方法依赖注入。
@RequiredArgsConstructor
public class AlertRuleController {

    private final AlertRuleService alertRuleService;

    /** 创建告警规则，仅 admin。 */
    // 生成 OpenAPI 端点文档，summary/description/operationId 与 openapi-alert.json 的 createAlertRule 操作对齐。
    // 创建规则仅管理员可访问，声明 Bearer JWT 认证。
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
            // 触发 CreateAlertRuleRequest 的 Bean Validation 校验。
            @Valid
            // 将 HTTP JSON 请求体反序列化为 CreateAlertRuleRequest。
            @RequestBody CreateAlertRuleRequest request,
            // 从 Spring SecurityContext 注入当前认证用户。
            @AuthenticationPrincipal AuthenticatedUser operator) {
        return com.susumonitor.server.common.ApiResponse.success(alertRuleService.createRule(request, operator.id()));
    }

    /** 查询所有未删除规则，已认证用户可查。 */
    // 生成 OpenAPI 端点文档，summary/description/operationId 与 openapi-alert.json 的 listAlertRules 操作对齐。
    // 规则查询任意已认证用户可访问，声明 Bearer JWT 认证。
    @Operation(
            summary = "List alert rules",
            description = "Returns all non-deleted alert rules, ordered by created_at DESC. "
                    + "Authenticated users only.",
            operationId = "listAlertRules",
            security = @SecurityRequirement(name = "bearerAuth"))
    // 声明列表接口的错误响应（HTTP 状态 + 业务错误码），与契约 responses 对齐。
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rule list"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired JWT (40100)")
    })
    @GetMapping
    public com.susumonitor.server.common.ApiResponse<List<AlertRuleVo>> listRules() {
        return com.susumonitor.server.common.ApiResponse.success(alertRuleService.listRules());
    }

    /** 更新规则，仅 admin。 */
    // 生成 OpenAPI 端点文档，summary/description/operationId 与 openapi-alert.json 的 updateAlertRule 操作对齐。
    // 更新规则仅管理员可访问，声明 Bearer JWT 认证。
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
            // 描述路径参数，约束与 @Positive 校验一致。
            @Parameter(description = "Rule ID, must be positive.")
            // 将路径参数绑定为规则 ID。
            @PathVariable("id")
            // 限制规则 ID 必须大于 0。
            @Positive Long ruleId,
            @Valid @RequestBody UpdateAlertRuleRequest request) {
        return com.susumonitor.server.common.ApiResponse.success(alertRuleService.updateRule(ruleId, request));
    }

    /** 软删除规则，仅 admin。 */
    // 生成 OpenAPI 端点文档，summary/description/operationId 与 openapi-alert.json 的 deleteAlertRule 操作对齐。
    // 删除规则仅管理员可访问，声明 Bearer JWT 认证。
    @Operation(
            summary = "Delete alert rule (soft delete)",
            description = "Soft-deletes the rule by setting deleted=1. Admin-only.",
            operationId = "deleteAlertRule",
            security = @SecurityRequirement(name = "bearerAuth"))
    // 声明删除接口的错误响应（HTTP 状态 + 业务错误码），与契约 responses 对齐。
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rule deleted"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired JWT (40100)"),
            @ApiResponse(responseCode = "403", description = "Authenticated but not an administrator (40300)"),
            @ApiResponse(responseCode = "404", description = "Resource not found (40400)")
    })
    @DeleteMapping("/{id}")
    public com.susumonitor.server.common.ApiResponse<Void> deleteRule(
            // 描述路径参数，约束与 @Positive 校验一致。
            @Parameter(description = "Rule ID, must be positive.")
            // 将路径参数绑定为规则 ID。
            @PathVariable("id")
            // 限制规则 ID 必须大于 0。
            @Positive Long ruleId) {
        alertRuleService.deleteRule(ruleId);
        return com.susumonitor.server.common.ApiResponse.success(null);
    }
}
