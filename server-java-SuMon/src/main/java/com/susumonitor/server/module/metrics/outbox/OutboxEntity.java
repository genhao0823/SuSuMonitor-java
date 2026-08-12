package com.susumonitor.server.module.metrics.outbox;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 映射 V14 创建的 message_outbox 表，一行表示一条待可靠投递的冻结事件。
 */
@Data
@TableName("message_outbox")
public class OutboxEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField("event_id")
    private String eventId;
    @TableField("event_type")
    private String eventType;
    @TableField("routing_key")
    private String routingKey;
    private String payload;
    private String status;
    private Integer attempts;
    @TableField("next_attempt_at")
    private LocalDateTime nextAttemptAt;
    @TableField("last_error")
    private String lastError;
    @TableField("published_at")
    private LocalDateTime publishedAt;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
