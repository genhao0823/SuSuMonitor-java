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
// 自动生成当前 DTO 的 getter、setter、toString、equals 和 hashCode 方法。
@Data
// 类级 @Schema 描述全量更新请求体，约束说明与契约 UpdateServerRequest 对齐，供 springdoc 生成请求模型。
@Schema(description = "全量更新服务器请求体：基础字段全部必填（description 可为空串），"
        + "凭据仅在 ssh_auth_type 不变时可省略以保留原值")
public class UpdateServerRequest {

    // 校验服务器名称不能为空、不能是空字符串或只包含空白字符。
    @NotBlank
    // 限制服务器名称最大长度为 100 个字符。
    @Size(max = 100)
    // 描述服务器名称字段，供 OpenAPI 文档展示。
    @Schema(description = "服务器名称", minLength = 1, maxLength = 100)
    private String name;

    // 校验服务器地址不能为空、不能是空字符串或只包含空白字符。
    @NotBlank
    // 限制服务器地址最大长度为 255 个字符。
    @Size(max = 255)
    // 描述服务器地址字段，供 OpenAPI 文档展示。
    @Schema(description = "服务器地址", minLength = 1, maxLength = 255)
    private String host;

    // PUT 要求显式提交服务器描述，但允许提交空描述。
    @NotNull
    // 限制服务器描述最大长度为 500 个字符。
    @Size(max = 500)
    // 描述服务器描述字段，PUT 语义为必填可空串，与校验注解一致。
    @Schema(description = "服务器描述（PUT 必填，可为空串）", maxLength = 500)
    private String description;

    // 校验 SSH 地址不能为空、不能是空字符串或只包含空白字符。
    @NotBlank
    // 限制 SSH 地址最大长度为 255 个字符。
    @Size(max = 255)
    // 将 Java 的 sshHost 属性映射为接口 JSON 字段 ssh_host。
    @JsonProperty("ssh_host")
    // 描述 SSH 连接地址字段，供 OpenAPI 文档展示。
    @Schema(description = "SSH 连接地址", minLength = 1, maxLength = 255)
    private String sshHost;

    // 校验 SSH 端口不能为 null。
    @NotNull
    // 限制 SSH 端口最小值为 1。
    @Min(1)
    // 限制 SSH 端口最大值为 65535。
    @Max(65535)
    // 将 Java 的 sshPort 属性映射为接口 JSON 字段 ssh_port。
    @JsonProperty("ssh_port")
    // 描述 SSH 端口字段，供 OpenAPI 文档展示。
    @Schema(description = "SSH 端口", minimum = "1", maximum = "65535")
    private Integer sshPort;

    // 校验 SSH 用户名不能为空、不能是空字符串或只包含空白字符。
    @NotBlank
    // 限制 SSH 用户名最大长度为 100 个字符。
    @Size(max = 100)
    // 将 Java 的 sshUser 属性映射为接口 JSON 字段 ssh_user。
    @JsonProperty("ssh_user")
    // 描述 SSH 登录用户名字段，供 OpenAPI 文档展示。
    @Schema(description = "SSH 登录用户名", minLength = 1, maxLength = 100)
    private String sshUser;

    // 校验 SSH 认证方式不能为空、不能是空字符串或只包含空白字符。
    @NotBlank
    // 限制 SSH 认证方式只能是 password 或 private_key。
    @Pattern(regexp = "^(password|private_key)$")
    // 将 Java 的 sshAuthType 属性映射为接口 JSON 字段 ssh_auth_type。
    @JsonProperty("ssh_auth_type")
    // 描述 SSH 认证方式字段，允许值即契约定义的两个枚举。
    @Schema(description = "SSH 认证方式", allowableValues = {"password", "private_key"})
    private String sshAuthType;

    // 将 Java 的 sshPassword 属性映射为接口 JSON 字段 ssh_password。
    @JsonProperty("ssh_password")
    // 限制 SSH 密码长度，省略和更新语义由 Service 校验。
    @Size(max = 1024)
    // 防止 Lombok 生成的 toString 方法输出 SSH 密码。
    @ToString.Exclude
    // 描述 SSH 密码凭据字段；writeOnly 表示只入不出，防止凭据出现在响应模型。
    @Schema(description = "密码认证凭据，永不返回或记录；省略表示保留原值", writeOnly = true, maxLength = 1024)
    private String sshPassword;

    // 将 Java 的 sshPrivateKey 属性映射为接口 JSON 字段 ssh_private_key。
    @JsonProperty("ssh_private_key")
    // 限制 SSH 私钥长度，省略和更新语义由 Service 校验。
    @Size(max = 65535)
    // 防止 Lombok 生成的 toString 方法输出 SSH 私钥。
    @ToString.Exclude
    // 描述 SSH 私钥凭据字段；writeOnly 表示只入不出，防止凭据出现在响应模型。
    @Schema(description = "私钥认证凭据，永不返回或记录；省略表示保留原值", writeOnly = true, maxLength = 65535)
    private String sshPrivateKey;

    // 将 Java 的 sshPrivateKeyPassphrase 属性映射为接口 JSON 字段 ssh_private_key_passphrase。
    @JsonProperty("ssh_private_key_passphrase")
    // 限制 SSH 私钥口令长度，省略和更新语义由 Service 校验。
    @Size(max = 1024)
    // 防止 Lombok 生成的 toString 方法输出 SSH 私钥口令。
    @ToString.Exclude
    // 描述 SSH 私钥口令字段；writeOnly 表示只入不出，防止凭据出现在响应模型。
    @Schema(description = "私钥口令（可选），永不返回或记录；省略表示保留原值", writeOnly = true, maxLength = 1024)
    private String sshPrivateKeyPassphrase;
}
