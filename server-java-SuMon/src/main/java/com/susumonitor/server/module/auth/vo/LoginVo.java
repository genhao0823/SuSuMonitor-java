package com.susumonitor.server.module.auth.vo;

import lombok.Data;
import lombok.ToString;

/**
 * 登录结果 VO，包含 Token 类型、有效时长和当前用户信息。
 *
 * <p>Token 字段排除在 toString 之外，防止 JWT 泄露到日志。</p>
 */
@Data
public class LoginVo {

    // 防止 Lombok 生成的 toString 方法输出 JWT。
    @ToString.Exclude
    private String token;

    private String tokenType;

    private long expiresIn;

    private CurrentUserVo user;

}
