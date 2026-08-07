package com.susumonitor.server.module.system.controller;

import com.susumonitor.server.common.ApiResponse;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.module.system.RabbitHealthChecker;
import com.susumonitor.server.module.system.vo.HealthStatusVo;
import com.susumonitor.server.module.system.vo.ReadyStatusVo;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    private final String applicationName;

    /**
     * 构造系统控制器，注入数据库源、可选 RabbitMQ 健康检查器和应用名称。
     *
     * @param dataSource         数据库数据源（就绪检查时验证连接有效性）
     * @param rabbitHealthChecker RabbitMQ 健康检查器（可选，未启用 Outbox 时为 null）
     * @param applicationName    应用名称（从配置读取，默认 susumonitor）
     */
    public SystemController(DataSource dataSource,
            ObjectProvider<RabbitHealthChecker> rabbitHealthChecker,
            @Value("${spring.application.name:susumonitor}") String applicationName) {
        this.dataSource = dataSource;
        this.rabbitHealthChecker = rabbitHealthChecker;
        this.applicationName = applicationName;
    }

    /**
     * 健康检查：返回应用存活状态和当前时间戳。
     *
     * @return 健康状态（始终返回 UP）
     */
    @GetMapping("/health")
    public ApiResponse<HealthStatusVo> health() {
        return ApiResponse.success(new HealthStatusVo("UP", applicationName, OffsetDateTime.now(ZoneOffset.UTC)));
    }

    /**
     * 就绪检查：数据库必须可用；Outbox 启用（存在 RabbitHealthChecker Bean）时
     * RabbitMQ 也必须可用——"存活但未就绪"语义，Broker 不可达返回 50301，
     * 应用不退出，发布器退避重试。
     */
    @GetMapping("/ready")
    public ApiResponse<ReadyStatusVo> ready() {
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
        return ApiResponse.success(new ReadyStatusVo("UP", "ok", OffsetDateTime.now(ZoneOffset.UTC)));
    }
}
