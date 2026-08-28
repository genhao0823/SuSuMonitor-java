package com.susumonitor.server.module.system.controller;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.alert.consume.ConsumeStatsService;
import com.susumonitor.server.module.alert.consume.ConsumeTimingStatsRegistry;
import com.susumonitor.server.module.system.QueueBacklogProbeService;
import com.susumonitor.server.module.system.QueueBacklogSnapshotRegistry;
import com.susumonitor.server.module.system.RabbitHealthChecker;
import com.susumonitor.server.module.system.RedisHealthChecker;
import com.susumonitor.server.module.system.vo.ConsumeStatsVo;
import com.susumonitor.server.module.system.vo.HealthStatusVo;
import com.susumonitor.server.module.system.vo.QueueBacklogVo;
import com.susumonitor.server.module.system.vo.ReadyStatusVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统控制器，提供健康检查和就绪检查等基础运维接口。
 *
 * <p>健康检查仅返回存活状态；就绪检查额外验证数据库和可选 RabbitMQ 的可用性。</p>
 */
@RestController
@RequestMapping("/api")
public class SystemController {

    private static final int DATABASE_VALIDATE_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;

    private final ObjectProvider<RabbitHealthChecker> rabbitHealthChecker;

    private final ObjectProvider<RedisHealthChecker> redisHealthChecker;

    private final ObjectProvider<ConsumeTimingStatsRegistry> consumeTimingStatsRegistry;

    private final ObjectProvider<ConsumeStatsService> consumeStatsService;

    private final ObjectProvider<QueueBacklogSnapshotRegistry> queueBacklogSnapshotRegistry;

    private final AppProperties appProperties;

    private final String applicationName;

    /**
     * 构造系统控制器，注入数据库源、可选 RabbitMQ 组件、监控注册表与配置。
     *
     * @param dataSource         数据库数据源（就绪检查时验证连接有效性）
     * @param rabbitHealthChecker RabbitMQ 健康检查器（可选，未启用 Outbox 时为 null）
     * @param applicationName    应用名称（从配置读取，默认 susumonitor）
     */
    public SystemController(DataSource dataSource,
            ObjectProvider<RabbitHealthChecker> rabbitHealthChecker,
            ObjectProvider<RedisHealthChecker> redisHealthChecker,
            @Value("${spring.application.name:susumonitor}") String applicationName,
            ObjectProvider<ConsumeTimingStatsRegistry> consumeTimingStatsRegistry,
            ObjectProvider<ConsumeStatsService> consumeStatsService,
            ObjectProvider<QueueBacklogSnapshotRegistry> queueBacklogSnapshotRegistry,
            AppProperties appProperties) {
        this.dataSource = dataSource;
        this.rabbitHealthChecker = rabbitHealthChecker;
        this.redisHealthChecker = redisHealthChecker;
        this.applicationName = applicationName;
        this.consumeTimingStatsRegistry = consumeTimingStatsRegistry;
        this.consumeStatsService = consumeStatsService;
        this.queueBacklogSnapshotRegistry = queueBacklogSnapshotRegistry;
        this.appProperties = appProperties;
    }

    /**
     * 健康检查：返回应用存活状态和当前时间戳。
     *
     * @return 健康状态（始终返回 UP）
     */
    // 生成 OpenAPI 端点文档，summary/description/operationId 与 openapi-system.json 的 getHealth 操作对齐。
    // 健康检查是公开端点（permitAll），不声明 security，契约中亦无认证要求。
    @Tag(name = "system", description = "System health and readiness checks")
    @Operation(
            summary = "Health check",
            description = "Checks whether the backend application is alive. "
                    + "This endpoint does not depend on the database.",
            operationId = "getHealth")
    // 声明健康检查的错误响应（HTTP 状态 + 业务错误码），与契约 responses 对齐。
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Application is alive"),
            @ApiResponse(responseCode = "500", description = "Internal server error (50000)")
    })
    @GetMapping("/health")
    public com.susumonitor.server.common.ApiResponse<HealthStatusVo> health() {
        return com.susumonitor.server.common.ApiResponse.success(new HealthStatusVo("UP", applicationName, OffsetDateTime.now(ZoneOffset.UTC)));
    }

    /**
     * 就绪检查：数据库必须可用；Outbox 启用（存在 RabbitHealthChecker Bean）时
     * RabbitMQ 也必须可用，Redis 启用（存在 RedisHealthChecker Bean）时 Redis 也必须可用——
     * "存活但未就绪"语义，Broker/Redis 不可达返回 50301/50302，应用不退出。
     */
    // 生成 OpenAPI 端点文档，summary/description/operationId 与 openapi-system.json 的 getReady 操作对齐。
    // 就绪检查是公开端点（permitAll），不声明 security，契约中亦无认证要求。
    @Tag(name = "system", description = "System health and readiness checks")
    @Operation(
            summary = "Readiness check",
            description = "Checks whether the backend application and database connection are ready. "
                    + "When the Outbox/RabbitMQ integration is enabled, it also requires the Broker "
                    + "health check to pass. A Broker failure leaves the process alive but returns "
                    + "readiness code 50301 and HTTP 503.",
            operationId = "getReady")
    // 声明就绪检查的错误响应（HTTP 状态 + 业务错误码），与契约 responses 对齐。
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Application, database, and enabled RabbitMQ integration are ready"),
            @ApiResponse(responseCode = "500",
                    description = "Database is unavailable (50001), Redis is enabled but unavailable "
                            + "(50302), or an internal server error occurred (50000)"),
            @ApiResponse(responseCode = "503", description = "RabbitMQ is enabled but unavailable (50301)")
    })
    @GetMapping("/ready")
    public com.susumonitor.server.common.ApiResponse<ReadyStatusVo> ready() {
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.isValid(DATABASE_VALIDATE_TIMEOUT_SECONDS)) {
                throw new BusinessException(ErrorCode.DATABASE_ERROR);
            }
        } catch (SQLException exception) {
            throw new BusinessException(ErrorCode.DATABASE_ERROR, exception);
        }
        RabbitHealthChecker checker = rabbitHealthChecker.getIfAvailable();
        if (checker != null && !checker.isHealthy()) {
            throw new BusinessException(ErrorCode.RABBITMQ_UNAVAILABLE);
        }
        RedisHealthChecker redisChecker = redisHealthChecker.getIfAvailable();
        if (redisChecker != null && !redisChecker.isHealthy()) {
            throw new BusinessException(ErrorCode.REDIS_UNAVAILABLE);
        }
        return com.susumonitor.server.common.ApiResponse.success(new ReadyStatusVo("UP", "ok", OffsetDateTime.now(ZoneOffset.UTC)));
    }

    /**
     * 消费统计快照（ADMIN，MVP-14 监控收尾）：耗时窗口（内存）与失败率窗口（DB 聚合）组合。
     *
     * <p>RabbitMQ 未启用时对应注册表/服务为空，返回空列表。</p>
     */
    // 生成 OpenAPI 端点文档，summary/description/operationId 与 openapi-system.json 的 getRabbitmqConsumers 操作对齐。
    // 监控快照仅管理员可访问，声明 Bearer JWT 认证。
    @Tag(name = "rabbitmq-monitor", description = "RabbitMQ runtime monitoring snapshots (ROLE_ADMIN only)")
    @Operation(
            summary = "Consume timing and failure-rate window snapshot",
            description = "Per-consumer processing timing window (in-memory) combined with the "
                    + "failure-rate window aggregated from message_consume_records (V15). "
                    + "Returns an empty list when the RabbitMQ integration is disabled.",
            operationId = "getRabbitmqConsumers",
            security = @SecurityRequirement(name = "bearerAuth"))
    // 声明消费统计接口的错误响应（HTTP 状态 + 业务错误码），与契约 responses 对齐。
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Consumer stats snapshot"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired JWT (40100)"),
            @ApiResponse(responseCode = "403", description = "Authenticated but not an administrator (40300)")
    })
    @GetMapping("/system/rabbitmq/consumers")
    public com.susumonitor.server.common.ApiResponse<List<ConsumeStatsVo>> rabbitmqConsumers() {
        ConsumeTimingStatsRegistry timingRegistry = consumeTimingStatsRegistry.getIfAvailable();
        ConsumeStatsService statsService = consumeStatsService.getIfAvailable();
        Map<String, ConsumeTimingStatsRegistry.ConsumerTimingSnapshot> timing =
                timingRegistry == null ? Map.of() : timingRegistry.snapshot();
        Map<String, ConsumeStatsService.WindowCounts> window =
                statsService == null ? Map.of() : statsService.windowCounts();

        Set<String> consumers = new HashSet<>();
        consumers.addAll(timing.keySet());
        consumers.addAll(window.keySet());

        List<ConsumeStatsVo> result = new ArrayList<>();
        for (String consumer : consumers) {
            ConsumeTimingStatsRegistry.ConsumerTimingSnapshot timingStats = timing.get(consumer);
            ConsumeStatsService.WindowCounts windowCounts = window.get(consumer);
            ConsumeStatsVo vo = new ConsumeStatsVo();
            vo.setConsumer(consumer);
            if (timingStats != null) {
                vo.setTotalCount(timingStats.totalCount());
                vo.setAvgMs(timingStats.totalCount() == 0 ? null : timingStats.totalMs() / timingStats.totalCount());
                vo.setMaxMs(timingStats.maxMs());
                vo.setLastSampleAt(timingStats.lastSampleAt());
            }
            if (windowCounts != null) {
                vo.setFailedWindow(windowCounts.failed());
                vo.setConsumedWindow(windowCounts.consumed());
                long total = windowCounts.failed() + windowCounts.consumed();
                if (total > 0) {
                    vo.setFailureRate(Math.round(windowCounts.failed() * 10000.0 / total) / 10000.0);
                }
            }
            result.add(vo);
        }
        return com.susumonitor.server.common.ApiResponse.success(result);
    }

    /**
     * 队列积压快照（ADMIN，MVP-14 监控收尾）：最近一轮被动声明探测结果。
     *
     * <p>RabbitMQ 未启用或无探测结果时返回空列表。</p>
     */
    // 生成 OpenAPI 端点文档，summary/description/operationId 与 openapi-system.json 的 getRabbitmqQueues 操作对齐。
    // 监控快照仅管理员可访问，声明 Bearer JWT 认证。
    @Tag(name = "rabbitmq-monitor", description = "RabbitMQ runtime monitoring snapshots (ROLE_ADMIN only)")
    @Operation(
            summary = "Queue backlog snapshot",
            description = "Latest passive-declare probe snapshot of the six frozen queues: "
                    + "business queues (type=business, warned when exceeding the threshold) and "
                    + "dead-letter queues (type=dead_letter). Returns an empty list when the RabbitMQ "
                    + "integration is disabled or no probe has run yet.",
            operationId = "getRabbitmqQueues",
            security = @SecurityRequirement(name = "bearerAuth"))
    // 声明队列积压接口的错误响应（HTTP 状态 + 业务错误码），与契约 responses 对齐。
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Queue backlog snapshot"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired JWT (40100)"),
            @ApiResponse(responseCode = "403", description = "Authenticated but not an administrator (40300)")
    })
    @GetMapping("/system/rabbitmq/queues")
    public com.susumonitor.server.common.ApiResponse<List<QueueBacklogVo>> rabbitmqQueues() {
        QueueBacklogSnapshotRegistry registry = queueBacklogSnapshotRegistry.getIfAvailable();
        if (registry == null) {
            return com.susumonitor.server.common.ApiResponse.success(List.of());
        }
        int warnThreshold = appProperties.getRabbitmq().getQueueBacklogWarnThreshold();
        List<QueueBacklogVo> result = new ArrayList<>();
        for (QueueBacklogSnapshotRegistry.QueueSnapshot snapshot : registry.snapshot().values()) {
            QueueBacklogVo vo = new QueueBacklogVo();
            vo.setQueue(snapshot.queue());
            vo.setType(QueueBacklogProbeService.BUSINESS_QUEUES.contains(snapshot.queue())
                    ? "business" : "dead_letter");
            vo.setMessages(snapshot.messages());
            vo.setWarnThreshold(warnThreshold);
            vo.setCheckedAt(snapshot.checkedAt());
            vo.setError(snapshot.error());
            result.add(vo);
        }
        return com.susumonitor.server.common.ApiResponse.success(result);
    }
}
