package com.susumonitor.server.module.admin.service;

import com.susumonitor.server.common.vo.PageResult;
import com.susumonitor.server.module.admin.vo.AdminUserVo;
import com.susumonitor.server.module.admin.vo.BatchReviewResult;
import com.susumonitor.server.module.auth.vo.CurrentUserVo;
import java.util.List;

/**
 * 定义管理员审核用户的业务契约，隔离 HTTP 调用方与具体持久化实现。
 */
public interface AdminUserService {

    /**
     * 分页查询普通用户（管理面列表，可按审核状态筛选）。
     *
     * @param status   审核状态（pending/approved/rejected），null 时不过滤
     * @param keyword  用户名模糊关键字，null/空白时不过滤
     * @param page     页码（从 1 起）
     * @param pageSize 每页大小
     * @return 分页结果
     */
    PageResult<AdminUserVo> pageUsers(String status, String keyword, int page, int pageSize);

    /**
     * 批量审核待审核用户（approve/reject）。
     *
     * <p>逐 id 原子审核：单个失败不影响其余项，返回处理统计与失败明细。</p>
     *
     * @param userIds        待审核用户 ID 列表（非空）
     * @param targetStatus   目标审核状态（approved/rejected）
     * @param operatorUserId 审核管理员 ID
     * @return 处理统计（processed/failed/failedIds）
     */
    BatchReviewResult batchUpdateReviewStatus(List<Long> userIds, String targetStatus, Long operatorUserId);

    /**
     * 审核通过待审核用户。
     *
     * @param userId 待审核用户 ID
     * @param operatorUserId 审核管理员 ID
     * @return 审核后的用户公开信息
     */
    CurrentUserVo approveUser(Long userId, Long operatorUserId);

    /**
     * 拒绝待审核用户。
     *
     * @param userId 待审核用户 ID
     * @param operatorUserId 审核管理员 ID
     * @return 审核后的用户公开信息
     */
    CurrentUserVo rejectUser(Long userId, Long operatorUserId);
}
