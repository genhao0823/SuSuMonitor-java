package com.susumonitor.server.module.alert.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 映射 V5 创建、V10 增加软删除字段的 alert_rules 表。
 *
 * <p>metric、operator、level 在数据库中为 VARCHAR，Entity 保持 String 类型，
 * 由 Service 和 AlertStateMachine 层转换为枚举。</p>
 */
@Data
@TableName("alert_rules")
public class AlertRuleEntity {

    // 主键 ID，自增。
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    // 服务器 ID，为 null 表示通用规则。
    @TableField("server_id")
    private Long serverId;
    // 告警指标: cpu/memory/disk/temperature/load。
    private String metric;
    // 比较操作符: >/>=/</<=。
    private String operator;
    // 告警阈值。
    @TableField("threshold_value")
    private BigDecimal thresholdValue;
    // 告警等级: warning/critical。
    private String level;
    // 连续越界确认次数: 1=首次越界即触发（默认/向后兼容）, >1=连续越界 N 次触发（逃逸窗口）。
    @TableField("confirm_count")
    private Integer confirmCount;
    // 是否启用: 0 否, 1 是。
    private Boolean enabled;
    // 软删除标记: 0 未删除, 1 已删除。
    private Boolean deleted;
    // 删除时间。
    @TableField("deleted_at")
    private LocalDateTime deletedAt;
    // 创建人用户 ID。
    @TableField("created_by")
    private Long createdBy;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
