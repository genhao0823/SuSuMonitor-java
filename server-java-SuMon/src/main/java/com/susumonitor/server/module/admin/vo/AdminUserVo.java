package com.susumonitor.server.module.admin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 管理面用户视图对象，供管理员分页查询接口返回。
 *
 * <p>仅包含非敏感字段：用户 ID、用户名、角色、审核状态和创建时间。</p>
 */
// 类级 @Schema 描述管理面用户模型，供 springdoc 生成 /api-docs 的响应模型说明。
@Data
@Schema(description = "管理面用户公开信息")
public class AdminUserVo {

    // 描述用户 ID 字段。
    @Schema(description = "用户 ID")
    private Long id;

    // 描述用户名字段。
    @Schema(description = "用户名")
    private String username;

    // 描述角色字段，允许值即契约定义的两个角色。
    @Schema(description = "角色", allowableValues = {"admin", "user"})
    private String role;

    // 描述审核状态字段，允许值即契约定义的三种审核状态。
    @Schema(description = "审核状态", allowableValues = {"pending", "approved", "rejected"})
    private String reviewStatus;

    // 描述创建时间字段。
    @Schema(description = "创建时间（UTC ISO-8601）")
    private OffsetDateTime createdAt;

}
