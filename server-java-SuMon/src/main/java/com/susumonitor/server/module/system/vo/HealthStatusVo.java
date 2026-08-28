package com.susumonitor.server.module.system.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/**
 * 健康检查响应 VO，包含应用存活状态、应用名称和时间戳。
 */
// 类级 @Schema 描述健康检查结果模型，供 springdoc 生成响应模型说明。
@Schema(description = "健康检查结果（仅存活状态，不依赖数据库）")
public class HealthStatusVo {

    @Schema(description = "存活状态，恒为 UP", example = "UP")
    private final String status;

    @Schema(description = "应用名称", example = "susumonitor")
    private final String application;

    @Schema(description = "当前时间戳（UTC ISO-8601）")
    private final OffsetDateTime timestamp;

    /**
     * 构造健康检查响应。
     *
     * @param status      应用存活状态
     * @param application 应用名称
     * @param timestamp   当前时间戳
     */
    public HealthStatusVo(String status, String application, OffsetDateTime timestamp) {
        this.status = status;
        this.application = application;
        this.timestamp = timestamp;
    }

    /**
     * 获取应用存活状态。
     *
     * @return 状态字符串
     */
    public String getStatus() {
        return status;
    }

    /**
     * 获取应用名称。
     *
     * @return 应用名称
     */
    public String getApplication() {
        return application;
    }

    /**
     * 获取当前时间戳。
     *
     * @return 时间戳
     */
    public OffsetDateTime getTimestamp() {
        return timestamp;
    }
}
