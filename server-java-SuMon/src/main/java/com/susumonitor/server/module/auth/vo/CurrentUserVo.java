package com.susumonitor.server.module.auth.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 当前用户公开信息 VO，用于登录后和 /me 接口返回。
 *
 * <p>仅包含非敏感字段：用户 ID、用户名、角色、审核状态和时间戳。</p>
 */
// 类级 @Schema 描述当前用户公开模型，供 springdoc 生成 /api-docs 的响应模型说明。
@Data
@Schema(description = "当前用户公开信息")
public class CurrentUserVo {

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

    // 描述审核时间字段，未审核时为 null。
    @Schema(description = "审核时间（未审核为 null）")
    private OffsetDateTime reviewedAt;

    // 描述创建时间字段。
    @Schema(description = "创建时间（UTC ISO-8601）")
    private OffsetDateTime createdAt;

}
