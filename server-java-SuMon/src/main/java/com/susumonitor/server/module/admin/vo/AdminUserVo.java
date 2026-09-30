package com.susumonitor.server.module.admin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 管理面用户视图对象，供管理员分页查询接口返回。
 *
 * <p>仅包含非敏感字段：用户 ID、用户名、角色、审核状态和创建时间。</p>
 */
@Data
@Schema(description = "管理面用户公开信息")
public class AdminUserVo {

    @Schema(description = "用户 ID")
    private Long id;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "角色", allowableValues = {"admin", "user"})
    private String role;

    @Schema(description = "审核状态", allowableValues = {"pending", "approved", "rejected"})
    private String reviewStatus;

    @Schema(description = "创建时间（UTC ISO-8601）")
    private OffsetDateTime createdAt;

}
