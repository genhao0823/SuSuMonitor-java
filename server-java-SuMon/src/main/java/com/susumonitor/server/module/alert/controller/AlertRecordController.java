package com.susumonitor.server.module.alert.controller;

import com.susumonitor.server.common.ApiResponse;
import com.susumonitor.server.common.vo.PageResult;
import com.susumonitor.server.module.alert.service.AlertRecordService;
import com.susumonitor.server.module.alert.vo.AlertNotificationVo;
import com.susumonitor.server.module.alert.vo.AlertRecordVo;
import com.susumonitor.server.security.AuthenticatedUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 告警记录 REST API，提供记录分页查询和标记已读。
 *
 * <p>已认证用户可查询和标记已读。</p>
 */
// 将当前类注册为 REST Controller，并将返回值写入 HTTP 响应体。
@RestController
// 为告警记录接口统一增加 /api/alerts/records 路径前缀。
@RequestMapping("/api/alerts/records")
// 启用方法参数约束，使非法分页参数返回参数错误。
@Validated
// 自动生成包含 final 字段的构造方法，用于构造方法依赖注入。
@RequiredArgsConstructor
public class AlertRecordController {

    private final AlertRecordService alertRecordService;

    /** 分页查询告警记录，支持按服务器和状态筛选。 */
    @GetMapping
    public ApiResponse<PageResult<AlertRecordVo>> listRecords(
            @RequestParam(value = "server_id", required = false) Long serverId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") @Min(1) Integer page,
            @RequestParam(value = "page_size", defaultValue = "20") @Min(1) @Max(100) Integer pageSize) {
        return ApiResponse.success(alertRecordService.listRecords(serverId, status, page, pageSize));
    }

    /** 标记告警记录为已读。 */
    @PutMapping("/{id}/read")
    public ApiResponse<Void> markAsRead(
            // 将路径参数绑定为记录 ID。
            @PathVariable("id")
            // 限制记录 ID 必须大于 0。
            @Positive Long recordId,
            // 从 Spring SecurityContext 注入当前认证用户。
            @AuthenticationPrincipal AuthenticatedUser operator) {
        alertRecordService.markAsRead(recordId, operator.id());
        return ApiResponse.success(null);
    }

    /** 查询某告警记录的通知投递历史（每条渠道一行，含尝试次数与失败原因）。 */
    @GetMapping("/{id}/notifications")
    public ApiResponse<List<AlertNotificationVo>> listNotifications(
            @PathVariable("id") @Positive Long recordId) {
        return ApiResponse.success(alertRecordService.listNotifications(recordId));
    }
}
