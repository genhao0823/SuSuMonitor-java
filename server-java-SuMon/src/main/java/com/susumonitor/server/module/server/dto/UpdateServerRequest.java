package com.susumonitor.server.module.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

/**
 * 接收服务器全量更新参数，凭据省略和认证方式切换语义由 Service 处理。
 */
@Data
@Schema(description = "全量更新服务器请求体：基础字段全部必填（description 可为空串），"
        + "凭据仅在 ssh_auth_type 不变时可省略以保留原值")
public class UpdateServerRequest {

    @NotBlank
    @Size(max = 100)
    @Schema(description = "服务器名称", minLength = 1, maxLength = 100)
    private String name;

    @NotBlank
    @Size(max = 255)
    @Schema(description = "服务器地址", minLength = 1, maxLength = 255)
    private String host;

    // PUT 要求显式提交服务器描述，但允许提交空描述。
    @NotNull
    @Size(max = 500)
    @Schema(description = "服务器描述（PUT 必填，可为空串）", maxLength = 500)
    private String description;

    @NotBlank
    @Size(max = 255)
    @JsonProperty("ssh_host")
    @Schema(description = "SSH 连接地址", minLength = 1, maxLength = 255)
    private String sshHost;

    @NotNull
    @Min(1)
    @Max(65535)
    @JsonProperty("ssh_port")
    @Schema(description = "SSH 端口", minimum = "1", maximum = "65535")
    private Integer sshPort;

    @NotBlank
    @Size(max = 100)
    @JsonProperty("ssh_user")
    @Schema(description = "SSH 登录用户名", minLength = 1, maxLength = 100)
    private String sshUser;

    @NotBlank
    @Pattern(regexp = "^(password|private_key)$")
    @JsonProperty("ssh_auth_type")
    @Schema(description = "SSH 认证方式", allowableValues = {"password", "private_key"})
    private String sshAuthType;

    @JsonProperty("ssh_password")
    // 限制 SSH 密码长度，省略和更新语义由 Service 校验。
    @Size(max = 1024)
    @ToString.Exclude
    @Schema(description = "密码认证凭据，永不返回或记录；省略表示保留原值", writeOnly = true, maxLength = 1024)
    private String sshPassword;

    @JsonProperty("ssh_private_key")
    // 限制 SSH 私钥长度，省略和更新语义由 Service 校验。
    @Size(max = 65535)
    @ToString.Exclude
    @Schema(description = "私钥认证凭据，永不返回或记录；省略表示保留原值", writeOnly = true, maxLength = 65535)
    private String sshPrivateKey;

    @JsonProperty("ssh_private_key_passphrase")
    // 限制 SSH 私钥口令长度，省略和更新语义由 Service 校验。
    @Size(max = 1024)
    @ToString.Exclude
    @Schema(description = "私钥口令（可选），永不返回或记录；省略表示保留原值", writeOnly = true, maxLength = 1024)
    private String sshPrivateKeyPassphrase;
}
