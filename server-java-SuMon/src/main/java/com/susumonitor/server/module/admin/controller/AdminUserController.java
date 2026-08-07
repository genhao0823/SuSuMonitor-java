package com.susumonitor.server.module.admin.controller;

import com.susumonitor.server.common.ApiResponse;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.common.vo.PageResult;
import com.susumonitor.server.module.admin.dto.BatchReviewRequest;
import com.susumonitor.server.module.admin.service.AdminUserService;
import com.susumonitor.server.module.admin.vo.AdminUserVo;
import com.susumonitor.server.module.admin.vo.BatchReviewResult;
import com.susumonitor.server.module.auth.vo.CurrentUserVo;
import com.susumonitor.server.security.AuthenticatedUser;
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
    @GetMapping("/users")
    public ApiResponse<PageResult<AdminUserVo>> listUsers(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") @Min(1) int page,
            @RequestParam(value = "page_size", defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        validateStatus(status);
        return ApiResponse.success(adminUserService.pageUsers(status, keyword, page, pageSize));
    }

    /**
     * 批量审核通过指定用户。
     *
     * @param request  批量审核请求（包含待审核用户 ID 列表）
     * @param operator 当前审核管理员
     * @return 处理统计（成功数 / 失败数 / 失败 ID 列表）
     */
    @PutMapping("/users/batch-approve")
    public ApiResponse<BatchReviewResult> batchApproveUsers(
            @Valid @RequestBody BatchReviewRequest request,
            @AuthenticationPrincipal AuthenticatedUser operator) {
        return ApiResponse.success(adminUserService.batchUpdateReviewStatus(
                request.getUserIds(), "approved", operator.id()));
    }

    /**
     * 批量拒绝指定用户。
     *
     * @param request  批量审核请求（包含待拒绝用户 ID 列表）
     * @param operator 当前审核管理员
     * @return 处理统计（成功数 / 失败数 / 失败 ID 列表）
     */
    @PutMapping("/users/batch-reject")
    public ApiResponse<BatchReviewResult> batchRejectUsers(
            @Valid @RequestBody BatchReviewRequest request,
            @AuthenticationPrincipal AuthenticatedUser operator) {
        return ApiResponse.success(adminUserService.batchUpdateReviewStatus(
                request.getUserIds(), "rejected", operator.id()));
    }

    /**
     * 审核通过指定用户。
     *
     * @param userId   目标用户 ID
     * @param operator 当前审核管理员
     * @return 审核后的用户公开信息
     */
    @PutMapping("/users/{id}/approve")
    public ApiResponse<CurrentUserVo> approveUser(
            @PathVariable("id")
            @Positive Long userId,
            @AuthenticationPrincipal AuthenticatedUser operator) {
        return ApiResponse.success(adminUserService.approveUser(userId, operator.id()));
    }

    /**
     * 拒绝指定用户。
     *
     * @param userId   目标用户 ID
     * @param operator 当前审核管理员
     * @return 审核后的用户公开信息
     */
    @PutMapping("/users/{id}/reject")
    public ApiResponse<CurrentUserVo> rejectUser(
            @PathVariable("id")
            @Positive Long userId,
            @AuthenticationPrincipal AuthenticatedUser operator) {
        return ApiResponse.success(adminUserService.rejectUser(userId, operator.id()));
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
