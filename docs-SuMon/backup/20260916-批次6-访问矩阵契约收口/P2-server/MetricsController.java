package com.susumonitor.server.module.metrics.controller;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.common.vo.PageResult;
import com.susumonitor.server.module.metrics.service.MetricsService;
import com.susumonitor.server.module.metrics.vo.MetricsHistoryVo;
import com.susumonitor.server.module.metrics.vo.MetricsLatestVo;
import com.susumonitor.server.module.metrics.vo.ProcessSnapshotVo;
import com.susumonitor.server.module.metrics.vo.ServerResourcesSnapshotVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 提供固定宽表 Metrics 的最新值和历史分页查询接口。 */
// 将指标端点归入 OpenAPI 文档的 servers 分组，与 openapi-server.json 契约的 tag 一致。
@Tag(name = "servers", description = "Secure server inventory management")
@RestController
@RequestMapping("/api/servers")
@Validated
@RequiredArgsConstructor
public class MetricsController {

    private final MetricsService metricsService;

    /** 查询服务器最新指标。 */
    // 生成 OpenAPI 端点文档，summary/description/operationId 与 openapi-server.json 的 getLatestMetrics 操作对齐。
    // 契约未提供 description，按 VO 语义补写。指标查询任意已认证用户可访问，声明 Bearer JWT 认证。
    @Operation(
            summary = "Get latest fixed-width metrics",
            description = "Returns the latest fixed-width metrics row for a server, "
                    + "including CPU, memory, disk, network, temperature and load average. "
                    + "Authenticated users only.",
            operationId = "getLatestMetrics",
            security = @SecurityRequirement(name = "bearerAuth"))
    // 声明最新指标接口的错误响应（HTTP 状态 + 业务错误码），与契约 responses 对齐。
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Latest metrics"),
            @ApiResponse(responseCode = "401", description = "Bearer JWT is missing, invalid, expired, or no longer authorized (40100)"),
            @ApiResponse(responseCode = "403", description = "Authenticated user is not an admin (40300)"),
            @ApiResponse(responseCode = "404", description = "Server does not exist or has been deleted (40400)")
    })
    @GetMapping("/{id}/metrics/latest")
    public com.susumonitor.server.common.ApiResponse<MetricsLatestVo> latest(
            // 描述路径参数，约束与 @Positive 校验一致。
            @Parameter(description = "Server ID.")
            @PathVariable("id") @Positive Long serverId) {
        return com.susumonitor.server.common.ApiResponse.success(metricsService.latest(serverId));
    }

    /** 查询服务器新鲜窗口内的实时 Top 进程快照。 */
    // 生成 OpenAPI 端点文档，summary/description/operationId 与 openapi-server.json 的 getLatestProcesses 操作对齐。
    // 进程快照为内存实时数据（90 秒新鲜窗口），任意已认证用户可访问，声明 Bearer JWT 认证。
    @Operation(
            summary = "Get latest in-memory top-process snapshot",
            description = "Returns the latest in-memory top-process snapshot for a server within the "
                    + "90-second freshness window, ranked by CPU and memory usage. Never persisted; "
                    + "404 when the Agent has not reported process data recently. Authenticated users only.",
            operationId = "getLatestProcesses",
            security = @SecurityRequirement(name = "bearerAuth"))
    // 声明进程快照接口的错误响应（HTTP 状态 + 业务错误码），与契约 responses 对齐。
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Latest top-process snapshot (90-second freshness window)"),
            @ApiResponse(responseCode = "401", description = "Bearer JWT is missing, invalid, expired, or no longer authorized (40100)"),
            @ApiResponse(responseCode = "403", description = "Authenticated user is not an admin (40300)"),
            @ApiResponse(responseCode = "404", description = "Server does not exist, or no fresh process snapshot exists (40400)")
    })
    @GetMapping("/{id}/processes/latest")
    public com.susumonitor.server.common.ApiResponse<ProcessSnapshotVo> latestProcesses(
            // 描述路径参数，约束与 @Positive 校验一致。
            @Parameter(description = "Server ID.")
            @PathVariable("id") @Positive Long serverId) {
        return com.susumonitor.server.common.ApiResponse.success(
                metricsService.latestProcessSnapshot(serverId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)));
    }

    /** 查询服务器新鲜窗口内的实时磁盘/网卡扩展资源快照。 */
    // 生成 OpenAPI 端点文档，summary/description/operationId 与 openapi-server.json 的 getLatestResources 操作对齐。
    // 扩展资源快照为内存实时数据（90 秒新鲜窗口），任意已认证用户可访问，声明 Bearer JWT 认证。
    @Operation(
            summary = "Get latest in-memory per-disk and per-nic resource snapshot",
            description = "Returns the latest in-memory per-disk and per-nic resource snapshot for a server "
                    + "within the 90-second freshness window. Never persisted; 404 when the Agent has not "
                    + "reported resource data recently. Authenticated users only.",
            operationId = "getLatestResources",
            security = @SecurityRequirement(name = "bearerAuth"))
    // 声明扩展资源快照接口的错误响应（HTTP 状态 + 业务错误码），与契约 responses 对齐。
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Latest extended-resource snapshot (90-second freshness window)"),
            @ApiResponse(responseCode = "401", description = "Bearer JWT is missing, invalid, expired, or no longer authorized (40100)"),
            @ApiResponse(responseCode = "403", description = "Authenticated user is not an admin (40300)"),
            @ApiResponse(responseCode = "404", description = "Server does not exist, or no fresh resource snapshot exists (40400)")
    })
    @GetMapping("/{id}/resources/latest")
    public com.susumonitor.server.common.ApiResponse<ServerResourcesSnapshotVo> latestResources(
            // 描述路径参数，约束与 @Positive 校验一致。
            @Parameter(description = "Server ID.")
            @PathVariable("id") @Positive Long serverId) {
        return com.susumonitor.server.common.ApiResponse.success(
                metricsService.latestResources(serverId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)));
    }

    /** 查询服务器历史指标。 */
    // 生成 OpenAPI 端点文档，summary/description/operationId 与 openapi-server.json 的 getMetricsHistory 操作对齐。
    // 契约未提供 description，按 VO 语义补写。指标查询任意已认证用户可访问，声明 Bearer JWT 认证。
    @Operation(
            summary = "Get historical fixed-width metrics",
            description = "Returns paginated historical fixed-width metrics for a server within the "
                    + "[start_time, end_time] range, newest first. Authenticated users only.",
            operationId = "getMetricsHistory",
            security = @SecurityRequirement(name = "bearerAuth"))
    // 声明历史指标接口的错误响应（HTTP 状态 + 业务错误码），与契约 responses 对齐。
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Metrics page"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameter (40002)"),
            @ApiResponse(responseCode = "401", description = "Bearer JWT is missing, invalid, expired, or no longer authorized (40100)"),
            @ApiResponse(responseCode = "403", description = "Authenticated user is not an admin (40300)"),
            @ApiResponse(responseCode = "404", description = "Server does not exist or has been deleted (40400)")
    })
    @GetMapping("/{id}/metrics")
    public com.susumonitor.server.common.ApiResponse<PageResult<MetricsHistoryVo>> history(
            // 描述路径参数；schema 由 springdoc 从 @Positive 校验自动推导。
            @Parameter(description = "Server ID.")
            @PathVariable("id") @Positive Long serverId,
            // 描述必填起始时间参数；schema 由 springdoc 从 OffsetDateTime 类型自动推导。
            @Parameter(description = "Start of the query window (inclusive), ISO-8601 date-time.",
                    required = true)
            @RequestParam("start_time") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startTime,
            // 描述必填结束时间参数；schema 由 springdoc 从 OffsetDateTime 类型自动推导。
            @Parameter(description = "End of the query window (inclusive), ISO-8601 date-time.",
                    required = true)
            @RequestParam("end_time") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endTime,
            // 描述页码参数；schema 由 springdoc 从 @RequestParam 默认值自动推导。
            @Parameter(description = "One-based page number.")
            @RequestParam(value = "page", defaultValue = "1") @Min(1) Integer page,
            // 描述每页大小参数；schema 由 springdoc 自动推导。
            @Parameter(description = "Number of items per page.")
            @RequestParam(value = "page_size", defaultValue = "20") @Min(1) @Max(100) Integer pageSize) {
        return com.susumonitor.server.common.ApiResponse.success(metricsService.history(serverId, startTime, endTime, page, pageSize));
    }
}
