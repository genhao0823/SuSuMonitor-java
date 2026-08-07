package com.susumonitor.server.module.admin.vo;

import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 管理面用户视图对象，供管理员分页查询接口返回。
 *
 * <p>仅包含非敏感字段：用户 ID、用户名、角色、审核状态和创建时间。</p>
 */
@Data
public class AdminUserVo {

    private Long id;

    private String username;

    private String role;

    private String reviewStatus;

    private OffsetDateTime createdAt;

}
