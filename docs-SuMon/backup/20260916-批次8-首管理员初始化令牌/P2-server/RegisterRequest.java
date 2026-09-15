package com.susumonitor.server.module.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

/**
 * 注册请求 DTO，接收用户名和密码并触发 Bean Validation 校验。
 *
 * <p>用户名仅允许英文字母、数字和下划线，长度为 3-50；
 * 密码长度 8-64，toString 排除密码哈希。</p>
 */
// 类级 @Schema 描述注册请求体，供 springdoc 生成 /api-docs 的请求模型说明。
@Data
@Schema(description = "注册请求体")
public class RegisterRequest {

    // 校验用户名不能为空、不能是空字符串或只包含空白字符。
    @NotBlank
    // 限制用户名长度为 3 到 50 个字符。
    @Size(min = 3, max = 50)
    // 限制用户名只能由英文字母、数字和下划线组成。
    @Pattern(regexp = "^[A-Za-z0-9_]+$")
    // 描述注册用户名字段，约束与校验注解一致，供 OpenAPI 文档展示。
    @Schema(description = "用户名，仅允许英文字母、数字和下划线", minLength = 3, maxLength = 50,
            pattern = "^[A-Za-z0-9_]+$")
    private String username;

    // 校验密码不能为空、不能是空字符串或只包含空白字符。
    @NotBlank
    // 限制密码长度为 8 到 64 个字符。
    @Size(min = 8, max = 64)
    // 防止 Lombok 生成的 toString 方法输出用户密码。
    @ToString.Exclude
    // 描述注册密码字段；format=password 使 Swagger UI 以密码框渲染，writeOnly 表示只入不出。
    @Schema(description = "密码（8-64 字符）", format = "password", minLength = 8, maxLength = 64,
            writeOnly = true)
    private String password;
}
