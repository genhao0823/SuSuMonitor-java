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
@Data
@Schema(description = "注册请求体")
public class RegisterRequest {

    @NotBlank
    @Size(min = 3, max = 50)
    @Pattern(regexp = "^[A-Za-z0-9_]+$")
    @Schema(description = "用户名，仅允许英文字母、数字和下划线", minLength = 3, maxLength = 50,
            pattern = "^[A-Za-z0-9_]+$")
    private String username;

    @NotBlank
    @Size(min = 8, max = 64)
    @ToString.Exclude
    @Schema(description = "密码（8-64 字符）", format = "password", minLength = 8, maxLength = 64,
            writeOnly = true)
    private String password;

    // 一次性初始化令牌为条件必填：仅在首管理员未初始化时由服务层强制校验，
    // 因此这里只约束长度范围（与签发值 32-128 字符一致）而不做 @NotBlank。
    @Size(min = 32, max = 128,
            message = "Bootstrap token must be between 32 and 128 characters")
    @ToString.Exclude
    @Schema(description = "一次性初始化令牌（仅首管理员未初始化时必填，"
            + "经服务器启动横幅或 AUTH_BOOTSTRAP_TOKEN 投递；首管理员存在后忽略）",
            format = "password", minLength = 32, maxLength = 128, writeOnly = true)
    private String bootstrapToken;
}
