package com.susumonitor.server.module.alert.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 映射 V10 创建的 alert_states 表，维护每条规则在每台服务器上的当前越界状态。
 *
 * <p>uk_alert_states_rule_server 唯一约束保证每条规则在每台服务器上
 * 最多一个状态行。version 字段用于手动乐观锁，不使用 MyBatis-Plus
 * 的 @Version 注解，因为项目使用 XML Mapper。</p>
 */
@Data
@TableName("alert_states")
public class AlertStateEntity {

    // 主键 ID，自增。
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    // 告警规则 ID。
    @TableField("rule_id")
    private Long ruleId;
    // 触发服务器 ID。
    @TableField("server_id")
    private Long serverId;
    // 是否处于越界状态: false 否, true 是。
    private Boolean active;
    // 当前连续越界计数（未达 confirm_count 时的累计值）。
    @TableField("breach_count")
    private Integer breachCount;
    // 当前活动告警记录 ID。
    @TableField("alert_record_id")
    private Long alertRecordId;
    // 本轮异常首次触发时间。
    @TableField("first_triggered_at")
    private LocalDateTime firstTriggeredAt;
    // 最近一次命中时间。
    @TableField("last_triggered_at")
    private LocalDateTime lastTriggeredAt;
    // 最近恢复时间。
    @TableField("resolved_at")
    private LocalDateTime resolvedAt;
    // 乐观锁版本号，SQL 中手动 WHERE version = #{version}。
    private Integer version;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
