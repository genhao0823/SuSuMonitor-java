package com.susumonitor.server.module.metrics.controller;

import com.susumonitor.server.security.AuthenticatedUser;
import com.susumonitor.server.websocket.MonitorTicketService;
import com.susumonitor.server.websocket.MonitorTicketVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 为已认证 Web 用户签发 Monitor WebSocket 一次性 ticket。 */
// 将 WebSocket ticket 端点归入 OpenAPI 文档的 servers 分组，与 openapi-server.json 契约的 tag 一致。
@Tag(name = "servers", description = "Secure server inventory management")
@RestController
@RequestMapping("/api/ws")
@RequiredArgsConstructor
public class MonitorTicketController {

    private final MonitorTicketService monitorTicketService;

    /** 签发 30 秒有效、只能使用一次的 Monitor ticket。 */
    // 生成 OpenAPI 端点文档，summary/description/operationId 与 openapi-server.json 的 issueMonitorTicket 操作对齐。
    // ticket 签发需要已认证用户，声明 Bearer JWT 认证。
    @Operation(
            summary = "Issue Monitor WebSocket ticket",
            description = "Issues a one-time ticket for /ws/monitor. The ticket expires exactly at "
                    + "expires_at, 30 seconds after issue. A long-lived JWT and Agent Token are never "
                    + "placed in the WebSocket URL.",
            operationId = "issueMonitorTicket",
            security = @SecurityRequirement(name = "bearerAuth"))
    // 声明签发接口的错误响应（HTTP 状态 + 业务错误码），与契约 responses 对齐。
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ticket issued"),
            @ApiResponse(responseCode = "401", description = "Bearer JWT is missing, invalid, expired, or no longer authorized (40100)"),
            @ApiResponse(responseCode = "403", description = "Authenticated user is not an admin (40300)")
    })
    @PostMapping("/monitor-ticket")
    public com.susumonitor.server.common.ApiResponse<MonitorTicketVo> issue(
            @AuthenticationPrincipal AuthenticatedUser user) {
        return com.susumonitor.server.common.ApiResponse.success(monitorTicketService.issue(user));
    }
}
