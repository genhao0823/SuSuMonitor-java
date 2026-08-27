package com.susumonitor.server.module.auth.dto;

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
@Data
public class RegisterRequest {

    // 校验用户名不能为空、不能是空字符串或只包含空白字符。
    @NotBlank
    // 限制用户名长度为 3 到 50 个字符。
    @Size(min = 3, max = 50)
    // 限制用户名只能由英文字母、数字和下划线组成。
    @Pattern(regexp = "^[A-Za-z0-9_]+$")
    private String username;

    // 校验密码不能为空、不能是空字符串或只包含空白字符。
    @NotBlank
    // 限制密码长度为 8 到 64 个字符。
    @Size(min = 8, max = 64)
    // 防止 Lombok 生成的 toString 方法输出用户密码。
    @ToString.Exclude
    private String password;
}
