package com.susumonitor.server.module.admin.service;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.common.vo.PageResult;
import com.susumonitor.server.module.admin.vo.BatchReviewResult;
import com.susumonitor.server.module.admin.vo.PendingUserVo;
import com.susumonitor.server.module.auth.entity.UserEntity;
import com.susumonitor.server.module.auth.service.UserService;
import com.susumonitor.server.module.auth.vo.CurrentUserVo;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 将当前类注册为 Spring Service Bean，承载管理员审核相关业务逻辑。
@Slf4j
@Service
// 自动生成包含 final 字段的构造方法，用于构造方法依赖注入。
@RequiredArgsConstructor
public class AdminUserServiceImpl implements AdminUserService {

    private static final String APPROVED_STATUS = "approved";

    private static final String REJECTED_STATUS = "rejected";

    private static final String PENDING_STATUS = "pending";

    private static final String USER_ROLE = "user";

    private static final ZoneId APPLICATION_ZONE = ZoneOffset.UTC;

    // users 表数据所有权归 auth 模块，审核数据访问统一走 UserService 契约。
    private final UserService userService;

    // 分页查询待审核用户，并按创建时间升序返回给管理员。
    @Transactional(readOnly = true)
    public PageResult<PendingUserVo> pagePendingUsers(String keyword, int page, int pageSize) {
        PageResult<UserEntity> raw = userService.pagePendingUsers(keyword, page, pageSize);
        PageResult<PendingUserVo> result = new PageResult<>();
        result.setItems(raw.getItems().stream().map(this::toPendingUserVo).toList());
        result.setTotal(raw.getTotal());
        result.setPage(raw.getPage());
        result.setPageSize(raw.getPageSize());
        return result;
    }

    // 将指定待审核用户标记为已审核状态，并记录审核人和审核时间。
    @Transactional
    public CurrentUserVo approveUser(Long userId, Long operatorUserId) {
        return updateUserReviewStatus(userId, operatorUserId, APPROVED_STATUS);
    }

    // 将指定待审核用户标记为已拒绝状态，并记录审核人和审核时间。
    @Transactional
    public CurrentUserVo rejectUser(Long userId, Long operatorUserId) {
        return updateUserReviewStatus(userId, operatorUserId, REJECTED_STATUS);
    }

    // 批量审核：逐 id 原子审核，单个失败累计到 failed 不整体回滚，便于前端展示部分成功。
    @Transactional
    public BatchReviewResult batchUpdateReviewStatus(List<Long> userIds, String targetStatus, Long operatorUserId) {
        if (userIds == null || userIds.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        if (operatorUserId == null || operatorUserId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        int processed = 0;
        int failed = 0;
        for (Long userId : userIds) {
            if (userId == null || userId <= 0) {
                failed++;
                continue;
            }
            try {
                updateUserReviewStatus(userId, operatorUserId, targetStatus);
                processed++;
            } catch (BusinessException exception) {
                log.warn("batch review skipped userId={}, target={}, reason={}",
                        userId, targetStatus, exception.getErrorCode());
                failed++;
            }
        }
        BatchReviewResult result = new BatchReviewResult();
        result.setProcessed(processed);
        result.setFailed(failed);
        return result;
    }

    // 按用户 ID 查询最新数据库状态，仅允许将待审核用户转换为 approved 或 rejected。
    private CurrentUserVo updateUserReviewStatus(Long userId, Long operatorUserId, String targetStatus) {
        if (operatorUserId == null || operatorUserId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        UserEntity userEntity = userService.getReviewUserById(userId);
        if (userEntity == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!USER_ROLE.equals(userEntity.getRole()) || !PENDING_STATUS.equals(userEntity.getReviewStatus())) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
        }
        LocalDateTime reviewedAt = LocalDateTime.now(ZoneOffset.UTC);
        if (!userService.updateReviewStatus(userId, targetStatus, operatorUserId, reviewedAt)) {
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
        }
        userEntity.setReviewStatus(targetStatus);
        userEntity.setReviewedBy(operatorUserId);
        userEntity.setReviewedAt(reviewedAt);
        return toCurrentUserVo(userEntity);
    }

    // 将待审核用户实体转换为接口响应对象，避免暴露密码哈希等敏感字段。
    private PendingUserVo toPendingUserVo(UserEntity userEntity) {
        PendingUserVo pendingUserVo = new PendingUserVo();
        pendingUserVo.setId(userEntity.getId());
        pendingUserVo.setUsername(userEntity.getUsername());
        pendingUserVo.setRole(userEntity.getRole());
        pendingUserVo.setReviewStatus(userEntity.getReviewStatus());
        pendingUserVo.setCreatedAt(toOffsetDateTime(userEntity.getCreatedAt()));
        return pendingUserVo;
    }

    // 按应用时区将数据库时间转换为接口时间。
    private OffsetDateTime toOffsetDateTime(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.atZone(APPLICATION_ZONE).toOffsetDateTime();
    }

    // 将审核后的用户转换为公开安全响应。
    private CurrentUserVo toCurrentUserVo(UserEntity userEntity) {
        CurrentUserVo currentUserVo = new CurrentUserVo();
        currentUserVo.setId(userEntity.getId());
        currentUserVo.setUsername(userEntity.getUsername());
        currentUserVo.setRole(userEntity.getRole());
        currentUserVo.setReviewStatus(userEntity.getReviewStatus());
        currentUserVo.setReviewedAt(toOffsetDateTime(userEntity.getReviewedAt()));
        currentUserVo.setCreatedAt(toOffsetDateTime(userEntity.getCreatedAt()));
        return currentUserVo;
    }
}