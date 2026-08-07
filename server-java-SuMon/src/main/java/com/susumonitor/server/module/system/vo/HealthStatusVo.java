package com.susumonitor.server.module.system.vo;

import java.time.OffsetDateTime;

/**
 * 健康检查响应 VO，包含应用存活状态、应用名称和时间戳。
 */
public class HealthStatusVo {

    private final String status;

    private final String application;

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
