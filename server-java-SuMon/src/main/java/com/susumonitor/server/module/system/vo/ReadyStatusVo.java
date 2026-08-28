package com.susumonitor.server.module.system.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/**
 * 就绪检查响应 VO，包含应用就绪状态、数据库状态和时间戳。
 */
// 类级 @Schema 描述就绪检查结果模型，供 springdoc 生成响应模型说明。
@Schema(description = "就绪检查结果（数据库必须可用）")
public class ReadyStatusVo {

    @Schema(description = "就绪状态，恒为 UP", example = "UP")
    private final String status;

    @Schema(description = "数据库状态描述", example = "ok")
    private final String database;

    @Schema(description = "当前时间戳（UTC ISO-8601）")
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
