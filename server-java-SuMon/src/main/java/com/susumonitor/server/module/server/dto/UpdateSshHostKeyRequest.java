package com.susumonitor.server.module.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 接收管理员通过可信带外渠道核对的 SSH 主机公钥指纹及显式轮换标记。
 */
// 自动生成请求字段访问方法，供 Spring MVC 反序列化和 Service 读取。
@Data
// 类级 @Schema 描述主机公钥确认请求体，供 springdoc 生成 /api-docs 的请求模型说明。
@Schema(description = "SSH 主机公钥确认请求体")
public class UpdateSshHostKeyRequest {

    // 要求管理员必须提供非空的预期指纹。
    @NotBlank
    // 只接受无填充 OpenSSH SHA-256 指纹，拒绝 MD5、SHA-1 和宽松格式。
    @Pattern(regexp = "^SHA256:[A-Za-z0-9+/]{43}$")
    // 将 Java 属性映射为接口 snake_case 字段。
    @JsonProperty("expected_fingerprint")
    // 描述预期指纹字段，约束与校验注解一致，供 OpenAPI 文档展示。
    @Schema(description = "管理员通过可信带外渠道核对的 OpenSSH SHA-256 主机公钥指纹",
            minLength = 50, maxLength = 50, pattern = "^SHA256:[A-Za-z0-9+/]{43}$")
    private String expectedFingerprint;

    // 描述轮换标记字段，供 OpenAPI 文档展示。
    @Schema(description = "true 表示在管理员核实合法主机密钥轮换后，替换不同的已登记指纹",
            defaultValue = "false")
    private boolean replace;
}
