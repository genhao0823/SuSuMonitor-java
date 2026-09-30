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
@Tag(name = "servers", description = "Secure server inventory management")
@RestController
@RequestMapping("/api/ws")
@RequiredArgsConstructor
public class MonitorTicketController {

    private final MonitorTicketService monitorTicketService;

    /** 签发 30 秒有效、只能使用一次的 Monitor ticket。 */
    @Operation(
            summary = "Issue Monitor WebSocket ticket",
            description = "Issues a one-time ticket for /ws/monitor. The ticket expires exactly at "
                    + "expires_at, 30 seconds after issue. A long-lived JWT and Agent Token are never "
                    + "placed in the WebSocket URL.",
            operationId = "issueMonitorTicket",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ticket issued"),
            @ApiResponse(responseCode = "401", description = "Bearer JWT is missing, invalid, expired, or no longer authorized (40100)")
    })
    @PostMapping("/monitor-ticket")
    public com.susumonitor.server.common.ApiResponse<MonitorTicketVo> issue(
            @AuthenticationPrincipal AuthenticatedUser user) {
        return com.susumonitor.server.common.ApiResponse.success(monitorTicketService.issue(user));
    }
}
