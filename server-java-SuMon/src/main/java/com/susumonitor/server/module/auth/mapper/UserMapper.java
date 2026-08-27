package com.susumonitor.server.module.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.susumonitor.server.module.auth.entity.UserEntity;
import java.util.List;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户数据访问接口，提供按用户名查询、按 ID 查询、分页查询与审核状态更新等数据库操作。
 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    /**
     * 按用户名查询用户，供注册查重和登录认证使用。
     *
     * @param username 用户名
     * @return 用户实体，不存在时返回 null
     */
    UserEntity selectByUsername(@Param("username") String username);

    /**
     * 按用户 ID 查询鉴权所需的最新状态，不读取密码哈希。
     *
     * @param userId 用户 ID
     * @return 用户实体，不存在时返回 null
     */
    UserEntity selectAuthenticationUserById(@Param("userId") Long userId);

    /**
     * 按 ID 查询审核目标的安全字段，不读取密码哈希。
     *
     * @param userId 用户 ID
     * @return 用户实体，不存在时返回 null
     */
    UserEntity selectReviewUserById(@Param("userId") Long userId);

    /**
     * 分页查询普通用户（管理面）。
     *
     * @param status   审核状态过滤（pending/approved/rejected），null 时不过滤
     * @param keyword  用户名模糊关键字，null 时不过滤
     * @param offset   偏移量
     * @param pageSize 每页大小
     * @return 用户实体列表
     */
    List<UserEntity> selectPageUsers(
            @Param("status") String status,
            @Param("keyword") String keyword,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize);

    /**
     * 统计普通用户总数（status/keyword 过滤与 selectPageUsers 一致）。
     *
     * @param status  审核状态过滤，null 时不过滤
     * @param keyword 用户名模糊关键字，null 时不过滤
     * @return 用户总数
     */
    long countUsers(@Param("status") String status, @Param("keyword") String keyword);

    /**
     * 仅当目标仍是待审核普通用户时原子更新审核结果。
     *
     * @param userId       用户 ID
     * @param reviewStatus 目标审核状态
     * @param reviewedBy   审核人 ID
     * @param reviewedAt   审核时间
     * @return 更新行数（0 表示状态已变化或行不存在）
     */
    int updateReviewStatus(
            @Param("userId") Long userId,
            @Param("reviewStatus") String reviewStatus,
            @Param("reviewedBy") Long reviewedBy,
            @Param("reviewedAt") LocalDateTime reviewedAt);
}
