package com.susumonitor.server.module.auth.vo;

import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 当前用户公开信息 VO，用于登录后和 /me 接口返回。
 *
 * <p>仅包含非敏感字段：用户 ID、用户名、角色、审核状态和时间戳。</p>
 */
@Data
public class CurrentUserVo {

    private Long id;

    private String username;

    private String role;

    private String reviewStatus;

    private OffsetDateTime reviewedAt;

    private OffsetDateTime createdAt;

}
