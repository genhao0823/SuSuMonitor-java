package com.susumonitor.server.module.alert.controller;

import com.susumonitor.server.common.vo.PageResult;
import com.susumonitor.server.module.alert.service.AlertRecordService;
import com.susumonitor.server.module.alert.vo.AlertNotificationVo;
import com.susumonitor.server.module.alert.vo.AlertRecordVo;
import com.susumonitor.server.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 告警记录 REST API，提供记录分页查询和标记已读。
 *
 * <p>已认证用户可查询和标记已读。</p>
 */
// 将告警记录端点归入 OpenAPI 文档的 alert 分组，与 openapi-alert.json 契约的 tag 一致。
@Tag(name = "alert", description = "Alert rules, records, and push")
// 将当前类注册为 REST Controller，并将返回值写入 HTTP 响应体。
@RestController
// 为告警记录接口统一增加 /api/alerts/records 路径前缀。
@RequestMapping("/api/alerts/records")
// 启用方法参数约束，使非法分页参数返回参数错误。
@Validated
// 自动生成包含 final 字段的构造方法，用于构造方法依赖注入。
@RequiredArgsConstructor
public class AlertRecordController {

    private final AlertRecordService alertRecordService;

    /** 分页查询告警记录，支持按服务器和状态筛选。 */
    // 生成 OpenAPI 端点文档，summary/description/operationId 与 openapi-alert.json 的 listAlertRecords 操作对齐。
    // 记录查询任意已认证用户可访问，声明 Bearer JWT 认证。
    @Operation(
            summary = "List alert records (paginated)",
            description = "Returns paginated alert records, optionally filtered by server_id and "
                    + "status (unread/read/resolved). Authenticated users only.",
            operationId = "listAlertRecords",
            security = @SecurityRequirement(name = "bearerAuth"))
    // 声明记录列表接口的错误响应（HTTP 状态 + 业务错误码），与契约 responses 对齐。
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paginated alert records"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameter (40002)"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired JWT (40100)")
    })
    @GetMapping
    public com.susumonitor.server.common.ApiResponse<PageResult<AlertRecordVo>> listRecords(
            // 描述服务器筛选参数。
            @Parameter(description = "Filter by server ID.")
            @RequestParam(value = "server_id", required = false) Long serverId,
            // 描述状态筛选参数，允许值写进描述；schema 由 springdoc 自动推导。
            @Parameter(description = "Filter by status (unread/read/resolved).")
            @RequestParam(value = "status", required = false) String status,
            // 描述页码参数；schema 由 springdoc 从 @RequestParam 默认值自动推导。
            @Parameter(description = "One-based page number.")
            @RequestParam(value = "page", defaultValue = "1") @Min(1) Integer page,
            // 描述每页大小参数；schema 由 springdoc 自动推导。
            @Parameter(description = "Number of items per page.")
            @RequestParam(value = "page_size", defaultValue = "20") @Min(1) @Max(100) Integer pageSize) {
        return com.susumonitor.server.common.ApiResponse.success(alertRecordService.listRecords(serverId, status, page, pageSize));
    }

    /** 标记告警记录为已读。 */
    // 生成 OpenAPI 端点文档，summary/description/operationId 与 openapi-alert.json 的 markAlertRecordAsRead 操作对齐。
    // 标记已读任意已认证用户可访问，声明 Bearer JWT 认证。
    @Operation(
            summary = "Mark alert record as read",
            description = "Marks an unread alert record as read. Already read or resolved records "
                    + "are not affected. Authenticated users only.",
            operationId = "markAlertRecordAsRead",
            security = @SecurityRequirement(name = "bearerAuth"))
    // 声明标记已读接口的错误响应（HTTP 状态 + 业务错误码），错误码以契约 README 全表为准。
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Record marked as read"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameter (40002)"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired JWT (40100)"),
            @ApiResponse(responseCode = "403", description = "Authenticated but not an administrator (40300)"),
            @ApiResponse(responseCode = "404", description = "Resource not found (40400)"),
            @ApiResponse(responseCode = "409", description = "Resource state conflict (40900)")
    })
    @PutMapping("/{id}/read")
    public com.susumonitor.server.common.ApiResponse<Void> markAsRead(
            // 描述路径参数，约束与 @Positive 校验一致。
            @Parameter(description = "Record ID, must be positive.")
            // 将路径参数绑定为记录 ID。
            @PathVariable("id")
            // 限制记录 ID 必须大于 0。
            @Positive Long recordId,
            // 从 Spring SecurityContext 注入当前认证用户。
            @AuthenticationPrincipal AuthenticatedUser operator) {
        alertRecordService.markAsRead(recordId, operator.id());
        return com.susumonitor.server.common.ApiResponse.success(null);
    }

    /** 查询某告警记录的通知投递历史（每条渠道一行，含尝试次数与失败原因）。 */
    // 生成 OpenAPI 端点文档，summary/description/operationId 与 openapi-alert.json 的 listAlertRecordNotifications 操作对齐。
    // 通知历史查询任意已认证用户可访问，声明 Bearer JWT 认证。
    @Operation(
            summary = "List alert record notification history",
            description = "Returns per-channel delivery attempts (status/attempts/last_error) for an "
                    + "alert record. Authenticated users only.",
            operationId = "listAlertRecordNotifications",
            security = @SecurityRequirement(name = "bearerAuth"))
    // 声明通知历史接口的错误响应（HTTP 状态 + 业务错误码），与契约 responses 对齐。
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Notification history returned"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameter (40002)"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired JWT (40100)"),
            @ApiResponse(responseCode = "404", description = "Resource not found (40400)")
    })
    @GetMapping("/{id}/notifications")
    public com.susumonitor.server.common.ApiResponse<List<AlertNotificationVo>> listNotifications(
            // 描述路径参数，约束与 @Positive 校验一致。
            @Parameter(description = "Record ID, must be positive.")
            @PathVariable("id") @Positive Long recordId) {
        return com.susumonitor.server.common.ApiResponse.success(alertRecordService.listNotifications(recordId));
    }
}
