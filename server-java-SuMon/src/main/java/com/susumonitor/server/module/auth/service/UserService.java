package com.susumonitor.server.module.auth.service;

import com.susumonitor.server.module.auth.dto.LoginRequest;
import com.susumonitor.server.module.auth.dto.RegisterRequest;
import com.susumonitor.server.module.auth.entity.UserEntity;
import com.susumonitor.server.module.auth.vo.CurrentUserVo;
import com.susumonitor.server.module.auth.vo.LoginVo;
import java.time.LocalDateTime;

/**
 * 定义认证用例的业务契约，供 HTTP 适配层依赖而不耦合具体实现。
 *
 * <p>users 表数据所有权归 auth 模块：管理面（admin）与终端（terminal）等
 * 模块必须通过本接口访问用户数据，不得直接注入 {@code UserMapper}。</p>
 */
public interface UserService {

    /**
     * 注册用户，并在首用户场景完成管理员初始化。
     *
     * <p>首管理员未初始化时要求请求携带有效一次性初始化令牌（批次 8），
     * 校验失败抛 40310/40311；首管理员已存在时令牌字段被忽略。</p>
     *
     * @param request 注册请求
     * @return 当前用户公开信息
     */
    CurrentUserVo register(RegisterRequest request);

    /**
     * 查询系统是否仍待初始化首管理员（公开状态端点用，无锁读）。
     *
     * @return true 表示注册需要一次性初始化令牌；状态行缺失按 false 防御处理
     */
    boolean getBootstrapPending();

    /**
     * 校验用户凭据并签发访问令牌。
     *
     * @param request 登录请求
     * @return 登录结果
     */
    LoginVo login(LoginRequest request);

    /**
     * 分页查询普通用户（管理面列表，status 可过滤审核状态）。
     *
     * @param status   审核状态过滤（pending/approved/rejected），null/空白时不过滤
     * @param keyword  用户名模糊关键字，null/空白时不过滤
     * @param page     页码（从 1 起）
     * @param pageSize 每页大小
     * @return 分页结果
     */
    com.susumonitor.server.common.vo.PageResult<UserEntity> pageUsers(
            String status, String keyword, int page, int pageSize);

    /**
     * 按 ID 查询用户（管理面审核前置校验用）。
     *
     * @param userId 用户 ID
     * @return 用户实体，不存在时返回 null
     */
    UserEntity getReviewUserById(Long userId);

    /**
     * 更新用户审核状态并记录审核人与审核时间。
     *
     * @param userId         被审核用户 ID
     * @param targetStatus  目标审核状态（approved/rejected）
     * @param operatorUserId 审核人 ID
     * @param reviewedAt     审核时间
     * @return 是否更新成功（0 表示状态已变化或行不存在）
     */
    boolean updateReviewStatus(Long userId, String targetStatus, Long operatorUserId, LocalDateTime reviewedAt);

    /**
     * 判断用户是否为已审核通过的普通用户（终端等能力校验用）。
     *
     * @param userId 用户 ID
     * @return 用户存在且 review_status=approved 时为 true
     */
    boolean isApprovedUser(Long userId);
}
