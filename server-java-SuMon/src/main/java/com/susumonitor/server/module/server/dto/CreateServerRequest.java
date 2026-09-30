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
 * 接收服务器创建参数，并通过字段约束完成基础格式校验。
 */
@Data
@Schema(description = "创建服务器请求体：与 ssh_auth_type 匹配的主凭据必填，"
        + "password 与 private_key 互斥，空白凭据非法")
public class CreateServerRequest {

    @NotBlank
    @Size(max = 100)
    @Schema(description = "服务器名称", minLength = 1, maxLength = 100)
    private String name;

    @NotBlank
    @Size(max = 255)
    @Schema(description = "服务器地址", minLength = 1, maxLength = 255)
    private String host;

    @Size(max = 500)
    @Schema(description = "服务器描述（可选）", maxLength = 500)
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
    @Schema(description = "SSH 端口（默认 22）", minimum = "1", maximum = "65535", defaultValue = "22")
    private Integer sshPort = 22;

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
    // 限制 SSH 密码长度，具体认证方式组合由 Service 校验。
    @Size(max = 1024)
    @ToString.Exclude
    @Schema(description = "密码认证凭据，永不返回或记录", writeOnly = true, maxLength = 1024)
    private String sshPassword;

    @JsonProperty("ssh_private_key")
    // 限制 SSH 私钥长度，具体认证方式组合由 Service 校验。
    @Size(max = 65535)
    @ToString.Exclude
    @Schema(description = "私钥认证凭据，永不返回或记录", writeOnly = true, maxLength = 65535)
    private String sshPrivateKey;

    @JsonProperty("ssh_private_key_passphrase")
    // 限制 SSH 私钥口令长度，具体认证方式组合由 Service 校验。
    @Size(max = 1024)
    @ToString.Exclude
    @Schema(description = "私钥口令（可选），永不返回或记录", writeOnly = true, maxLength = 1024)
    private String sshPrivateKeyPassphrase;
}
