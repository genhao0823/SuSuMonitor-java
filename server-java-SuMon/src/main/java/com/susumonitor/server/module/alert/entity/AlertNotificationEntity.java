package com.susumonitor.server.module.alert.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 映射 V21 创建的 alert_notifications 表：一次告警每个通知渠道一行。
 *
 * <p>status 为 pending（待发送/待重试）/ sent（已送达）/ failed（达重试上限放弃）。
 * attempts 记录已尝试次数；next_attempt_at 为 null 表示不再重试。</p>
 */
@Data
@TableName("alert_notifications")
public class AlertNotificationEntity {

    // 主键 ID，自增。
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    // 关联告警记录 ID。
    @TableField("alert_record_id")
    private Long alertRecordId;
    // 通知渠道: email/dingtalk/webhook。
    private String channel;
    // 投递状态: pending/sent/failed。
    private String status;
    // 已尝试次数。
    private Integer attempts;
    // 下次重试时间；null=不再重试。
    @TableField("next_attempt_at")
    private LocalDateTime nextAttemptAt;
    // 最近失败原因（截断 500）。
    @TableField("last_error")
    private String lastError;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
