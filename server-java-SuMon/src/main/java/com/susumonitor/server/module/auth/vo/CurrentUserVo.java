package com.susumonitor.server.module.auth.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 当前用户公开信息 VO，用于登录后和 /me 接口返回。
 *
 * <p>仅包含非敏感字段：用户 ID、用户名、角色、审核状态和时间戳。</p>
 */
@Data
@Schema(description = "当前用户公开信息")
public class CurrentUserVo {

    @Schema(description = "用户 ID")
    private Long id;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "角色", allowableValues = {"admin", "user"})
    private String role;

    @Schema(description = "审核状态", allowableValues = {"pending", "approved", "rejected"})
    private String reviewStatus;

    @Schema(description = "审核时间（未审核为 null）")
    private OffsetDateTime reviewedAt;

    @Schema(description = "创建时间（UTC ISO-8601）")
    private OffsetDateTime createdAt;

}
