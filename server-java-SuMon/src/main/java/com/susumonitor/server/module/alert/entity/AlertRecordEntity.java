package com.susumonitor.server.module.alert.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 映射 V5 创建的 alert_records 表，一行表示一次告警触发记录。
 *
 * <p>status 在数据库中为 VARCHAR，值为 unread/read/resolved。
 * 恢复时由评估器自动将 unread/read 改为 resolved。</p>
 */
@Data
@TableName("alert_records")
public class AlertRecordEntity {

    // 主键 ID，自增。
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    // 告警规则 ID，为 null 表示规则已删除但记录保留。
    @TableField("rule_id")
    private Long ruleId;
    // 服务器 ID。
    @TableField("server_id")
    private Long serverId;
    // 告警指标。
    private String metric;
    // 触发时当前值。
    @TableField("current_value")
    private BigDecimal currentValue;
    // 触发阈值。
    @TableField("threshold_value")
    private BigDecimal thresholdValue;
    // 告警等级。
    private String level;
    // 告警状态: unread/read/resolved。
    private String status;
    // 告警消息。
    private String message;
    // 标记已读用户 ID。
    @TableField("read_by")
    private Long readBy;
    // 标记已读时间。
    @TableField("read_at")
    private LocalDateTime readAt;
    // 告警触发时间。
    @TableField("triggered_at")
    private LocalDateTime triggeredAt;
    // 告警恢复时间；未恢复（status != resolved）时为 null。
    @TableField("resolved_at")
    private LocalDateTime resolvedAt;
    // 外部通知发送完成时间；未发送过通知时为 null。
    @TableField("notified_at")
    private LocalDateTime notifiedAt;
    // 已发出通知的渠道，逗号分隔（email/dingtalk/webhook）；未发送过通知时为 null。
    @TableField("notify_channels")
    private String notifyChannels;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
