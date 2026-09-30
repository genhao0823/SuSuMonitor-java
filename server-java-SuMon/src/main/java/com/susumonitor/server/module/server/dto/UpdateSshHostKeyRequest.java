package com.susumonitor.server.module.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 接收管理员通过可信带外渠道核对的 SSH 主机公钥指纹及显式轮换标记。
 */
@Data
@Schema(description = "SSH 主机公钥确认请求体")
public class UpdateSshHostKeyRequest {

    @NotBlank
    // 只接受无填充 OpenSSH SHA-256 指纹，拒绝 MD5、SHA-1 和宽松格式。
    @Pattern(regexp = "^SHA256:[A-Za-z0-9+/]{43}$")
    @JsonProperty("expected_fingerprint")
    @Schema(description = "管理员通过可信带外渠道核对的 OpenSSH SHA-256 主机公钥指纹",
            minLength = 50, maxLength = 50, pattern = "^SHA256:[A-Za-z0-9+/]{43}$")
    private String expectedFingerprint;

    @Schema(description = "true 表示在管理员核实合法主机密钥轮换后，替换不同的已登记指纹",
            defaultValue = "false")
    private boolean replace;
}
