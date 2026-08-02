package com.susumonitor.server.module.admin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.common.vo.PageResult;
import com.susumonitor.server.module.admin.vo.BatchReviewResult;
import com.susumonitor.server.module.admin.vo.PendingUserVo;
import com.susumonitor.server.module.auth.entity.UserEntity;
import com.susumonitor.server.module.auth.service.UserService;
import com.susumonitor.server.module.auth.vo.CurrentUserVo;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 验证管理员审核状态机、分页搜索与批量审核分支。
 */
// 启用 Mockito 扩展，为测试创建 Mapper 替身。
@ExtendWith(MockitoExtension.class)
class AdminUserServiceTests {

    // 隔离真实数据库，精确验证审核业务分支。
    @Mock
    private UserService userService;

    private AdminUserService adminUserService;

    // 在每个测试前创建管理员服务。
    @BeforeEach
    void setUp() {
        adminUserService = new AdminUserServiceImpl(userService);
    }

    // 验证待审核分页列表转换安全 VO 并透传分页信息。
    @Test
    void pagePendingUsersShouldReturnMappedPage() {
        PageResult<UserEntity> page = new PageResult<>();
        page.setItems(List.of(user("pending", "user")));
        page.setTotal(1);
        page.setPage(1);
        page.setPageSize(20);
        when(userService.pagePendingUsers("alice", 1, 20)).thenReturn(page);

        PageResult<PendingUserVo> result = adminUserService.pagePendingUsers("alice", 1, 20);

        assertEquals(1, result.getItems().size());
        assertEquals(1, result.getTotal());
        assertEquals(1, result.getPage());
        assertEquals(20, result.getPageSize());
    }

    // 验证批量审核全部成功时 processed 计数。
    @Test
    void batchApproveAllSuccessShouldCountProcessed() {
        when(userService.getReviewUserById(2L)).thenReturn(user("pending", "user"));
        when(userService.getReviewUserById(3L)).thenReturn(user("pending", "user"));
        when(userService.updateReviewStatus(any(), anyString(), any(), any())).thenReturn(true);

        BatchReviewResult result = adminUserService.batchUpdateReviewStatus(List.of(2L, 3L), "approved", 1L);

        assertEquals(2, result.getProcessed());
        assertEquals(0, result.getFailed());
    }

    // 验证批量审核单个失败（状态已变/不存在）累计到 failed 不中断整体。
    @Test
    void batchApprovePartialFailureShouldCountFailed() {
        when(userService.getReviewUserById(2L)).thenReturn(user("approved", "user"));
        when(userService.getReviewUserById(3L)).thenReturn(user("pending", "user"));
        when(userService.updateReviewStatus(eq(3L), any(), any(), any())).thenReturn(true);

        BatchReviewResult result = adminUserService.batchUpdateReviewStatus(List.of(2L, 3L), "approved", 1L);

        assertEquals(1, result.getProcessed());
        assertEquals(1, result.getFailed());
    }

    // 验证批量审核空列表返回参数错误。
    @Test
    void batchReviewEmptyListShouldThrow() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> adminUserService.batchUpdateReviewStatus(List.of(), "approved", 1L));

        assertEquals(ErrorCode.INVALID_REQUEST_PARAMETER, exception.getErrorCode());
    }

    // 验证批量审核非法操作人返回未授权。
    @Test
    void batchReviewInvalidOperatorShouldThrow() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> adminUserService.batchUpdateReviewStatus(List.of(2L), "approved", null));

        assertEquals(ErrorCode.UNAUTHORIZED, exception.getErrorCode());
    }

    // 验证批准用户记录审核状态和时间。
    @Test
    void approvePendingUserShouldSucceed() {
        when(userService.getReviewUserById(2L)).thenReturn(user("pending", "user"));
        when(userService.updateReviewStatus(any(), anyString(), any(), any())).thenReturn(true);

        CurrentUserVo result = adminUserService.approveUser(2L, 1L);

        assertEquals("approved", result.getReviewStatus());
        assertNotNull(result.getReviewedAt());
    }

    // 验证拒绝用户返回 rejected。
    @Test
    void rejectPendingUserShouldSucceed() {
        when(userService.getReviewUserById(2L)).thenReturn(user("pending", "user"));
        when(userService.updateReviewStatus(any(), anyString(), any(), any())).thenReturn(true);

        assertEquals("rejected", adminUserService.rejectUser(2L, 1L).getReviewStatus());
    }

    // 验证不存在目标返回 404。
    @Test
    void missingUserShouldReturnNotFound() {
        when(userService.getReviewUserById(99L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> adminUserService.approveUser(99L, 1L));

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.getErrorCode());
    }

    // 验证已审核用户返回冲突。
    @Test
    void reviewedUserShouldReturnConflict() {
        when(userService.getReviewUserById(2L)).thenReturn(user("approved", "user"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> adminUserService.rejectUser(2L, 1L));

        assertEquals(ErrorCode.RESOURCE_CONFLICT, exception.getErrorCode());
    }

    // 验证管理员目标返回冲突。
    @Test
    void adminTargetShouldReturnConflict() {
        when(userService.getReviewUserById(2L)).thenReturn(user("pending", "admin"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> adminUserService.approveUser(2L, 1L));

        assertEquals(ErrorCode.RESOURCE_CONFLICT, exception.getErrorCode());
    }

    // 验证并发条件更新失败返回冲突。
    @Test
    void concurrentUpdateShouldReturnConflict() {
        when(userService.getReviewUserById(2L)).thenReturn(user("pending", "user"));
        when(userService.updateReviewStatus(any(), anyString(), any(), any())).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> adminUserService.approveUser(2L, 1L));

        assertEquals(ErrorCode.RESOURCE_CONFLICT, exception.getErrorCode());
    }

    // 创建审核目标用户。
    private UserEntity user(String status, String role) {
        UserEntity user = new UserEntity();
        user.setId(2L);
        user.setUsername("review_target");
        user.setRole(role);
        user.setReviewStatus(status);
        user.setCreatedAt(LocalDateTime.now());
        return user;
    }
}
