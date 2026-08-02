package com.susumonitor.server.module.admin.controller;

import com.susumonitor.server.common.ApiResponse;
import com.susumonitor.server.common.vo.PageResult;
import com.susumonitor.server.module.admin.dto.BatchReviewRequest;
import com.susumonitor.server.module.admin.service.AdminUserService;
import com.susumonitor.server.module.admin.vo.BatchReviewResult;
import com.susumonitor.server.module.admin.vo.PendingUserVo;
import com.susumonitor.server.module.auth.vo.CurrentUserVo;
import com.susumonitor.server.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
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

// 将当前类注册为 REST Controller，并将返回值写入 HTTP 响应体。
@RestController
// 为管理员接口统一增加 /api/admin 路径前缀。
@RequestMapping("/api/admin")
// 启用方法参数约束，使非正数用户 ID 返回参数错误。
@Validated
// 自动生成包含 final 字段的构造方法，用于构造方法依赖注入。
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    // 将 GET /api/admin/users/pending 映射到当前方法，支持分页与用户名搜索。
    @GetMapping("/users/pending")
    public ApiResponse<PageResult<PendingUserVo>> pendingUsers(
            // 用户名模糊关键字，可选；空白时不过滤。
            @RequestParam(value = "keyword", required = false) String keyword,
            // 页码，从 1 起。
            @RequestParam(value = "page", defaultValue = "1") @Min(1) int page,
            // 每页大小，限制 1~100。
            @RequestParam(value = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return ApiResponse.success(adminUserService.pagePendingUsers(keyword, page, pageSize));
    }

    // 将 PUT /api/admin/users/batch-approve 映射到当前方法，批量审核通过。
    @PutMapping("/users/batch-approve")
    public ApiResponse<BatchReviewResult> batchApproveUsers(
            @Valid @RequestBody BatchReviewRequest request,
            @AuthenticationPrincipal AuthenticatedUser operator) {
        return ApiResponse.success(adminUserService.batchUpdateReviewStatus(
                request.getUserIds(), "approved", operator.id()));
    }

    // 将 PUT /api/admin/users/batch-reject 映射到当前方法，批量拒绝。
    @PutMapping("/users/batch-reject")
    public ApiResponse<BatchReviewResult> batchRejectUsers(
            @Valid @RequestBody BatchReviewRequest request,
            @AuthenticationPrincipal AuthenticatedUser operator) {
        return ApiResponse.success(adminUserService.batchUpdateReviewStatus(
                request.getUserIds(), "rejected", operator.id()));
    }

    // 将 PUT /api/admin/users/{id}/approve 映射到当前方法。
    @PutMapping("/users/{id}/approve")
    public ApiResponse<CurrentUserVo> approveUser(
            // 将路径参数绑定为目标用户 ID。
            @PathVariable("id")
            // 限制目标用户 ID 必须大于 0。
            @Positive Long userId,
            @AuthenticationPrincipal AuthenticatedUser operator) {
        return ApiResponse.success(adminUserService.approveUser(userId, operator.id()));
    }

    // 将 PUT /api/admin/users/{id}/reject 映射到当前方法。
    @PutMapping("/users/{id}/reject")
    public ApiResponse<CurrentUserVo> rejectUser(
            // 将路径参数绑定为目标用户 ID。
            @PathVariable("id")
            // 限制目标用户 ID 必须大于 0。
            @Positive Long userId,
            @AuthenticationPrincipal AuthenticatedUser operator) {
        return ApiResponse.success(adminUserService.rejectUser(userId, operator.id()));
    }
}
