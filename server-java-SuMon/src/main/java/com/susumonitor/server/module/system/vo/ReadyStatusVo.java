package com.susumonitor.server.module.system.vo;

import java.time.OffsetDateTime;

/**
 * 就绪检查响应 VO，包含应用就绪状态、数据库状态和时间戳。
 */
public class ReadyStatusVo {

    private final String status;

    private final String database;

    private final OffsetDateTime timestamp;

    /**
     * 构造就绪检查响应。
     *
     * @param status    应用就绪状态
     * @param database  数据库状态描述
     * @param timestamp 当前时间戳
     */
    public ReadyStatusVo(String status, String database, OffsetDateTime timestamp) {
        this.status = status;
        this.database = database;
        this.timestamp = timestamp;
    }

    /**
     * 获取应用就绪状态。
     *
     * @return 状态字符串
     */
    public String getStatus() {
        return status;
    }

    /**
     * 获取数据库状态描述。
     *
     * @return 数据库状态
     */
    public String getDatabase() {
        return database;
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
