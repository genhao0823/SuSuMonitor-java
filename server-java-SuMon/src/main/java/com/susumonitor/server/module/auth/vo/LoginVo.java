package com.susumonitor.server.module.auth.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.ToString;

/**
 * 登录结果 VO，包含 Token 类型、有效时长和当前用户信息。
 *
 * <p>Token 字段排除在 toString 之外，防止 JWT 泄露到日志。</p>
 */
@Data
@Schema(description = "登录结果：JWT 及当前用户信息")
public class LoginVo {

    @ToString.Exclude
    @Schema(description = "JWT bearer token，永不记录日志或由其他端点返回")
    private String token;

    @Schema(description = "Token 类型", example = "Bearer")
    private String tokenType;

    @Schema(description = "Token 有效期（秒）")
    private long expiresIn;

    @Schema(description = "当前用户信息")
    private CurrentUserVo user;

}
