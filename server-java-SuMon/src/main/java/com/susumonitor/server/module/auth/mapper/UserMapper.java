package com.susumonitor.server.module.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.susumonitor.server.module.auth.entity.UserEntity;
import java.util.List;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

// 将当前接口注册为 MyBatis Mapper，使 Spring 能够注入并调用数据库访问方法。
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    // 按用户名查询用户，供注册查重和登录认证使用。
    UserEntity selectByUsername(@Param("username") String username);

    // 按用户 ID 查询鉴权所需的最新状态，不读取密码哈希。
    UserEntity selectAuthenticationUserById(@Param("userId") Long userId);

    // 按 ID 查询审核目标的安全字段，不读取密码哈希。
    UserEntity selectReviewUserById(@Param("userId") Long userId);

    // 分页查询普通用户（管理面），status 可选过滤待审核/已通过/已拒绝，keyword 可选按用户名模糊匹配。
    List<UserEntity> selectPageUsers(
            @Param("status") String status,
            @Param("keyword") String keyword,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize);

    // 统计普通用户总数（status/keyword 过滤与 selectPageUsers 一致）。
    long countUsers(@Param("status") String status, @Param("keyword") String keyword);

    // 仅当目标仍是待审核普通用户时原子更新审核结果。
    int updateReviewStatus(
            @Param("userId") Long userId,
            @Param("reviewStatus") String reviewStatus,
            @Param("reviewedBy") Long reviewedBy,
            @Param("reviewedAt") LocalDateTime reviewedAt);
}
