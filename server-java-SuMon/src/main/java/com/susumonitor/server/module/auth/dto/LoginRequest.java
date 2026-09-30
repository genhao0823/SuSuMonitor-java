package com.susumonitor.server.module.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

/**
 * 接收登录用户名和密码，并限制敏感字段进入字符串表示。
 */
@Data
@Schema(description = "登录请求体")
public class LoginRequest {

    @NotBlank
    // 限制登录用户名不超过数据库字段长度，避免异常大的查询输入。
    @Size(max = 50)
    @Schema(description = "登录用户名", minLength = 1, maxLength = 50)
    private String username;

    @NotBlank
    // 限制登录密码长度，避免异常大的输入进入 BCrypt 校验。
    @Size(max = 64)
    @ToString.Exclude
    @Schema(description = "登录密码", format = "password", minLength = 1, maxLength = 64, writeOnly = true)
    private String password;
}
