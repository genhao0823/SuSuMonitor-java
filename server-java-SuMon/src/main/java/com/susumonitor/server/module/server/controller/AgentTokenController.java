package com.susumonitor.server.module.server.controller;

import com.susumonitor.server.module.server.service.AgentTokenService;
import com.susumonitor.server.module.server.vo.AgentTokenVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

/**
 * 提供管理员使用的 Agent Token 注册、轮换和撤销接口。
 */
@Tag(name = "servers", description = "Secure server inventory management")
@RestController
@RequestMapping("/api/servers")
@Validated
@RequiredArgsConstructor
public class AgentTokenController {

    private final AgentTokenService agentTokenService;

    /** 首次生成 Agent Token。 */
    @Operation(
            summary = "Register Agent Token",
            description = "Admin only. Generates a one-time Agent Token. "
                    + "The database stores only its SHA-256 hash.",
            operationId = "registerAgentToken",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token generated"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameter (40002)"),
            @ApiResponse(responseCode = "401", description = "Bearer JWT is missing, invalid, expired, or no longer authorized (40100)"),
            @ApiResponse(responseCode = "403", description = "Authenticated user is not an admin (40300)"),
            @ApiResponse(responseCode = "404", description = "Server does not exist or has been deleted (40400)"),
            @ApiResponse(responseCode = "409", description = "Server state conflicts with the request (40900)")
    })
    @PostMapping("/{id}/agent/register")
    public com.susumonitor.server.common.ApiResponse<AgentTokenVo> register(
            @Parameter(description = "Server ID.")
            @PathVariable("id") @Positive Long serverId) {
        return com.susumonitor.server.common.ApiResponse.success(agentTokenService.register(serverId));
    }

    /** 显式轮换已有 Agent Token。 */
    @Operation(
            summary = "Rotate Agent Token",
            description = "Admin only. Replaces the existing Agent Token. The previous token keeps "
                    + "authenticating handshakes for a short grace window "
                    + "(susumonitor.agent.token-grace-seconds, default 300s) so online agents can "
                    + "reconnect without manual re-registration; after the window the old token is "
                    + "rejected. Revoking the token invalidates both immediately.",
            operationId = "rotateAgentToken",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token rotated"),
            @ApiResponse(responseCode = "401", description = "Bearer JWT is missing, invalid, expired, or no longer authorized (40100)"),
            @ApiResponse(responseCode = "403", description = "Authenticated user is not an admin (40300)"),
            @ApiResponse(responseCode = "404", description = "Server does not exist or has been deleted (40400)"),
            @ApiResponse(responseCode = "409", description = "Server state conflicts with the request (40900)")
    })
    @PostMapping("/{id}/agent/rotate")
    public com.susumonitor.server.common.ApiResponse<AgentTokenVo> rotate(
            @Parameter(description = "Server ID.")
            @PathVariable("id") @Positive Long serverId) {
        return com.susumonitor.server.common.ApiResponse.success(agentTokenService.rotate(serverId));
    }

    /** 撤销当前 Agent Token。 */
    @Operation(
            summary = "Revoke Agent Token",
            description = "Admin only. Revokes the current Agent Token and marks the Agent offline.",
            operationId = "revokeAgentToken",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token revoked"),
            @ApiResponse(responseCode = "401", description = "Bearer JWT is missing, invalid, expired, or no longer authorized (40100)"),
            @ApiResponse(responseCode = "403", description = "Authenticated user is not an admin (40300)"),
            @ApiResponse(responseCode = "404", description = "Server does not exist or has been deleted (40400)"),
            @ApiResponse(responseCode = "409", description = "Server state conflicts with the request (40900)")
    })
    @DeleteMapping("/{id}/agent/revoke")
    public com.susumonitor.server.common.ApiResponse<Void> revoke(
            @Parameter(description = "Server ID.")
            @PathVariable("id") @Positive Long serverId) {
        agentTokenService.revoke(serverId);
        return com.susumonitor.server.common.ApiResponse.success(null);
    }
}
