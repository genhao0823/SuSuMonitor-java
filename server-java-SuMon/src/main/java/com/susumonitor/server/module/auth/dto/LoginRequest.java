package com.susumonitor.server.module.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

/**
 * 接收登录用户名和密码，并限制敏感字段进入字符串表示。
 */
// 类级 @Schema 描述登录请求体，供 springdoc 生成 /api-docs 的请求模型说明。
@Data
@Schema(description = "登录请求体")
public class LoginRequest {

    // 校验登录用户名不能为空、不能是空字符串或只包含空白字符。
    @NotBlank
    // 限制登录用户名不超过数据库字段长度，避免异常大的查询输入。
    @Size(max = 50)
    // 描述登录用户名字段，供 OpenAPI 文档展示。
    @Schema(description = "登录用户名", minLength = 1, maxLength = 50)
    private String username;

    // 校验登录密码不能为空、不能是空字符串或只包含空白字符。
    @NotBlank
    // 限制登录密码长度，避免异常大的输入进入 BCrypt 校验。
    @Size(max = 64)
    // 防止 Lombok 生成的 toString 方法输出用户密码。
    @ToString.Exclude
    // 描述登录密码字段；format=password 使 Swagger UI 以密码框渲染，writeOnly 表示只入不出。
    @Schema(description = "登录密码", format = "password", minLength = 1, maxLength = 64, writeOnly = true)
    private String password;
}
