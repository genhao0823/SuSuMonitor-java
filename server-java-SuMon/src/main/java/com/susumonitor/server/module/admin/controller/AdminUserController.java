package com.susumonitor.server.module.admin.controller;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.common.vo.PageResult;
import com.susumonitor.server.module.admin.dto.BatchReviewRequest;
import com.susumonitor.server.module.admin.service.AdminUserService;
import com.susumonitor.server.module.admin.vo.AdminUserVo;
import com.susumonitor.server.module.admin.vo.BatchReviewResult;
import com.susumonitor.server.module.auth.vo.CurrentUserVo;
import com.susumonitor.server.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员用户审核控制器，提供用户列表分页查询、批量审核和单用户审核接口。
 *
 * <p>所有管理接口统一以 /api/admin 为路径前缀，仅允许管理员角色访问。</p>
 */
// 将管理接口归入 OpenAPI 文档的 admin 分组，与 openapi-admin.json 契约的 tag 一致。
@Tag(name = "admin", description = "Administrator user-review operations (ROLE_ADMIN only)")
@RestController
@RequestMapping("/api/admin")
@Validated
@RequiredArgsConstructor
public class AdminUserController {

    private static final Set<String> VALID_STATUSES = Set.of("pending", "approved", "rejected");

    private final AdminUserService adminUserService;

    /**
     * 分页查询普通用户列表（管理面），支持审核状态筛选和用户名模糊搜索。
     *
     * @param status   审核状态过滤（pending/approved/rejected），空时不过滤
     * @param keyword  用户名模糊关键字，空时不过滤
     * @param page     页码（从 1 起）
     * @param pageSize 每页大小（1~100）
     * @return 分页结果
     */
    // 生成 OpenAPI 端点文档，summary/description/operationId 与 openapi-admin.json 的 listUsers 操作对齐。
    // 管理接口需要 Bearer JWT，声明 security 使 Swagger UI 标记为需要认证。
    @Operation(
            summary = "List users (paginated, status filter, keyword search)",
            description = "管理员用户列表：按审核状态筛选 + 用户名搜索 + 分页。Admin only.",
            operationId = "listUsers",
            security = @SecurityRequirement(name = "bearerAuth"))
    // 声明用户列表接口的错误响应（HTTP 状态 + 业务错误码），与契约 responses 对齐。
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pending user list returned"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameter (40002)"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired JWT (40100)"),
            @ApiResponse(responseCode = "403", description = "Authenticated but not an administrator (40300)")
    })
    @GetMapping("/users")
    public com.susumonitor.server.common.ApiResponse<PageResult<AdminUserVo>> listUsers(
            // 描述审核状态筛选参数，允许值写进描述；schema 由 springdoc 自动推导。
            @Parameter(description = "审核状态筛选（可选，缺省不过滤；pending/approved/rejected）")
            @RequestParam(value = "status", required = false) String status,
            // 描述用户名模糊关键字参数。
            @Parameter(description = "用户名模糊关键字（可选）")
            @RequestParam(value = "keyword", required = false) String keyword,
            // 描述页码参数；schema 由 springdoc 从 @RequestParam 默认值自动推导。
            @Parameter(description = "页码（从 1 起）")
            @RequestParam(value = "page", defaultValue = "1") @Min(1) int page,
            // 描述每页大小参数；schema 由 springdoc 自动推导。
            @Parameter(description = "每页大小（1~100）")
            @RequestParam(value = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        validateStatus(status);
        return com.susumonitor.server.common.ApiResponse.success(adminUserService.pageUsers(status, keyword, page, pageSize));
    }

    /**
     * 批量审核通过指定用户。
     *
     * @param request  批量审核请求（包含待审核用户 ID 列表）
     * @param operator 当前审核管理员
     * @return 处理统计（成功数 / 失败数 / 失败 ID 列表）
     */
    // 生成 OpenAPI 端点文档，summary/description/operationId 与 openapi-admin.json 的 batchApproveUsers 操作对齐。
    @Operation(
            summary = "Batch approve pending users",
            description = "批量approve待审核用户，逐 id 原子审核，single failure 不影响其余项。Admin only.",
            operationId = "batchApproveUsers",
            security = @SecurityRequirement(name = "bearerAuth"))
    // 声明批量审核接口的错误响应（HTTP 状态 + 业务错误码），错误码以契约 README 全表为准。
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Batch approve result returned"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameter (40002)"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired JWT (40100)"),
            @ApiResponse(responseCode = "403", description = "Authenticated but not an administrator (40300)"),
            @ApiResponse(responseCode = "404", description = "Resource not found (40400)"),
            @ApiResponse(responseCode = "409", description = "Resource state conflict (40900)")
    })
    @PutMapping("/users/batch-approve")
    public com.susumonitor.server.common.ApiResponse<BatchReviewResult> batchApproveUsers(
            @Valid @RequestBody BatchReviewRequest request,
            @AuthenticationPrincipal AuthenticatedUser operator) {
        return com.susumonitor.server.common.ApiResponse.success(adminUserService.batchUpdateReviewStatus(
                request.getUserIds(), "approved", operator.id()));
    }

    /**
     * 批量拒绝指定用户。
     *
     * @param request  批量审核请求（包含待拒绝用户 ID 列表）
     * @param operator 当前审核管理员
     * @return 处理统计（成功数 / 失败数 / 失败 ID 列表）
     */
    // 生成 OpenAPI 端点文档，summary/description/operationId 与 openapi-admin.json 的 batchRejectUsers 操作对齐。
    @Operation(
            summary = "Batch reject pending users",
            description = "批量reject待审核用户，逐 id 原子审核，single failure 不影响其余项。Admin only.",
            operationId = "batchRejectUsers",
            security = @SecurityRequirement(name = "bearerAuth"))
    // 声明批量审核接口的错误响应（HTTP 状态 + 业务错误码），错误码以契约 README 全表为准。
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Batch reject result returned"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameter (40002)"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired JWT (40100)"),
            @ApiResponse(responseCode = "403", description = "Authenticated but not an administrator (40300)"),
            @ApiResponse(responseCode = "404", description = "Resource not found (40400)"),
            @ApiResponse(responseCode = "409", description = "Resource state conflict (40900)")
    })
    @PutMapping("/users/batch-reject")
    public com.susumonitor.server.common.ApiResponse<BatchReviewResult> batchRejectUsers(
            @Valid @RequestBody BatchReviewRequest request,
            @AuthenticationPrincipal AuthenticatedUser operator) {
        return com.susumonitor.server.common.ApiResponse.success(adminUserService.batchUpdateReviewStatus(
                request.getUserIds(), "rejected", operator.id()));
    }

    /**
     * 审核通过指定用户。
     *
     * @param userId   目标用户 ID
     * @param operator 当前审核管理员
     * @return 审核后的用户公开信息
     */
    // 生成 OpenAPI 端点文档，summary/description/operationId 与 openapi-admin.json 的 approveUser 操作对齐。
    @Operation(
            summary = "Approve a pending user",
            description = "Transitions the target user from 'pending' to 'approved'. "
                    + "Returns the updated user snapshot. Admin only.",
            operationId = "approveUser",
            security = @SecurityRequirement(name = "bearerAuth"))
    // 声明单用户审核接口的错误响应（HTTP 状态 + 业务错误码），与契约 responses 对齐。
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User approved"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameter (40002)"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired JWT (40100)"),
            @ApiResponse(responseCode = "403", description = "Authenticated but not an administrator (40300)"),
            @ApiResponse(responseCode = "404", description = "Resource not found (40400)"),
            @ApiResponse(responseCode = "409", description = "Resource state conflict (40900)")
    })
    @PutMapping("/users/{id}/approve")
    public com.susumonitor.server.common.ApiResponse<CurrentUserVo> approveUser(
            // 描述路径参数；schema 由 springdoc 从 @Positive 校验自动推导。
            @Parameter(description = "Target user ID, must be positive.")
            @PathVariable("id")
            @Positive Long userId,
            @AuthenticationPrincipal AuthenticatedUser operator) {
        return com.susumonitor.server.common.ApiResponse.success(adminUserService.approveUser(userId, operator.id()));
    }

    /**
     * 拒绝指定用户。
     *
     * @param userId   目标用户 ID
     * @param operator 当前审核管理员
     * @return 审核后的用户公开信息
     */
    // 生成 OpenAPI 端点文档，summary/description/operationId 与 openapi-admin.json 的 rejectUser 操作对齐。
    @Operation(
            summary = "Reject a pending user",
            description = "Transitions the target user from 'pending' to 'rejected'. "
                    + "Returns the updated user snapshot. Admin only.",
            operationId = "rejectUser",
            security = @SecurityRequirement(name = "bearerAuth"))
    // 声明单用户审核接口的错误响应（HTTP 状态 + 业务错误码），与契约 responses 对齐。
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User rejected"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameter (40002)"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired JWT (40100)"),
            @ApiResponse(responseCode = "403", description = "Authenticated but not an administrator (40300)"),
            @ApiResponse(responseCode = "404", description = "Resource not found (40400)"),
            @ApiResponse(responseCode = "409", description = "Resource state conflict (40900)")
    })
    @PutMapping("/users/{id}/reject")
    public com.susumonitor.server.common.ApiResponse<CurrentUserVo> rejectUser(
            // 描述路径参数；schema 由 springdoc 从 @Positive 校验自动推导。
            @Parameter(description = "Target user ID, must be positive.")
            @PathVariable("id")
            @Positive Long userId,
            @AuthenticationPrincipal AuthenticatedUser operator) {
        return com.susumonitor.server.common.ApiResponse.success(adminUserService.rejectUser(userId, operator.id()));
    }

    /**
     * 校验审核状态枚举值，非法值返回参数错误。
     *
     * @param status 审核状态值
     */
    private void validateStatus(String status) {
        if (status != null && !status.isBlank() && !VALID_STATUSES.contains(status.trim())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
    }
}
