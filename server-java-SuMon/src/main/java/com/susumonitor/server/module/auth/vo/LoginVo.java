package com.susumonitor.server.module.auth.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.ToString;

/**
 * 登录结果 VO，包含 Token 类型、有效时长和当前用户信息。
 *
 * <p>Token 字段排除在 toString 之外，防止 JWT 泄露到日志。</p>
 */
// 类级 @Schema 描述登录响应数据模型，供 springdoc 生成 /api-docs 的响应模型说明。
@Data
@Schema(description = "登录结果：JWT 及当前用户信息")
public class LoginVo {

    // 防止 Lombok 生成的 toString 方法输出 JWT。
    @ToString.Exclude
    // 描述 token 字段，注明永不被日志记录或其他端点返回。
    @Schema(description = "JWT bearer token，永不记录日志或由其他端点返回")
    private String token;

    // 描述 token 类型字段，固定为 Bearer。
    @Schema(description = "Token 类型", example = "Bearer")
    private String tokenType;

    // 描述有效时长字段，单位秒。
    @Schema(description = "Token 有效期（秒）")
    private long expiresIn;

    // 描述当前用户字段，引用 CurrentUserVo 模型。
    @Schema(description = "当前用户信息")
    private CurrentUserVo user;

}
