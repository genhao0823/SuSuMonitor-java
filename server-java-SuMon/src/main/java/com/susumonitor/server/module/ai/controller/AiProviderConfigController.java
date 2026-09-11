package com.susumonitor.server.module.ai.controller;

import com.susumonitor.server.common.ApiResponse;
import com.susumonitor.server.module.ai.dto.TestAiProviderConfigRequest;
import com.susumonitor.server.module.ai.dto.UpsertAiProviderConfigRequest;
import com.susumonitor.server.module.ai.service.AiUserProviderConfigService;
import com.susumonitor.server.module.ai.vo.AiProviderConfigTestVo;
import com.susumonitor.server.module.ai.vo.AiProviderConfigVo;
import com.susumonitor.server.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员个人 AI 服务商配置端点。
 *
 * <p>每个管理员可保存自己的 OpenAI 兼容 endpoint/API Key/模型；同步 AI 接口
 * （诊断、问答、命令建议）按"个人配置优先，全局配置兜底"解析。api_key 永不回传
 * 明文（查询仅掩码；更新留空表示保留）。随 {@code susumonitor.ai.enabled} 开关装配。</p>
 */
@Tag(name = "ai-provider-config", description = "Per-admin AI provider configuration")
@RestController
@RequestMapping("/api/ai/provider-config")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "susumonitor.ai.enabled", havingValue = "true")
public class AiProviderConfigController {

    private final AiUserProviderConfigService configService;

    /** 查询当前管理员的个人 AI 配置（api_key 仅掩码）。 */
    @Operation(summary = "Get my AI provider configuration",
            description = "Returns the caller's personal provider config; the API key is masked.",
            operationId = "getAiProviderConfig", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping
    public ApiResponse<AiProviderConfigVo> getConfig(
            @AuthenticationPrincipal AuthenticatedUser operator) {
        return ApiResponse.success(configService.getVo(operator.id()));
    }

    /** 保存（或覆盖）当前管理员的个人 AI 配置；api_key 留空表示保留已存 Key。 */
    @Operation(summary = "Save my AI provider configuration",
            description = "Upserts the caller's personal provider config; blank api_key keeps the stored key.",
            operationId = "saveAiProviderConfig", security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping
    public ApiResponse<AiProviderConfigVo> saveConfig(
            @AuthenticationPrincipal AuthenticatedUser operator,
            @Valid @RequestBody UpsertAiProviderConfigRequest request) {
        configService.upsert(operator.id(), request.getBaseUrl(), request.getApiKey(),
                request.getModel(), !Boolean.FALSE.equals(request.getEnabled()));
        return ApiResponse.success(configService.getVo(operator.id()));
    }

    /** 删除当前管理员的个人 AI 配置（回退全局配置）；data 为是否实际删除。 */
    @Operation(summary = "Delete my AI provider configuration",
            description = "Removes the caller's personal provider config; falls back to global config.",
            operationId = "deleteAiProviderConfig", security = @SecurityRequirement(name = "bearerAuth"))
    @DeleteMapping
    public ApiResponse<Boolean> deleteConfig(
            @AuthenticationPrincipal AuthenticatedUser operator) {
        boolean removed = configService.deleteByUserId(operator.id());
        return ApiResponse.success(removed);
    }

    /** 用给定参数发起一次最小真实调用，验证 endpoint/Key/模型连通性。 */
    @Operation(summary = "Test an AI provider configuration",
            description = "Performs one minimal chat call; blank api_key reuses the stored key.",
            operationId = "testAiProviderConfig", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/test")
    public ApiResponse<AiProviderConfigTestVo> testConfig(
            @AuthenticationPrincipal AuthenticatedUser operator,
            @Valid @RequestBody TestAiProviderConfigRequest request) {
        return ApiResponse.success(configService.test(operator.id(), request.getBaseUrl(),
                request.getApiKey(), request.getModel()));
    }
}
