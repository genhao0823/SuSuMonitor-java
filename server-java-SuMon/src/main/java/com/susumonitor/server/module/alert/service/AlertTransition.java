package com.susumonitor.server.module.alert.service;

import com.susumonitor.server.module.alert.entity.AlertRuleEntity;
import com.susumonitor.server.module.alert.entity.AlertStateEntity;
import java.math.BigDecimal;

/**
 * 告警状态迁移决策，由 {@link AlertStateMachine} 评估后返回。
 *
 * <p>使用 sealed interface + 同文件 record，编译器强制 exhaustive switch，
 * 新增迁移类型时所有使用处编译报错。四种迁移携带不同数据：</p>
 * <ul>
 *   <li>Trigger — 首次越界，需创建 alert_record 和 active alert_state</li>
 *   <li>ContinueBreached — 持续越界，仅更新 last_triggered_at</li>
 *   <li>Resolve — 恢复，标记 record resolved 和 state inactive</li>
 *   <li>NoAction — 无需操作</li>
 * </ul>
 */
public sealed interface AlertTransition {

    /** 首次越界：从无活跃状态到触发告警。 */
    record Trigger(AlertRuleEntity rule, BigDecimal currentValue) implements AlertTransition {
    }

    /** 持续越界：已有活跃状态，继续越界，不创建新 record。 */
    record ContinueBreached(AlertStateEntity state, BigDecimal currentValue) implements AlertTransition {
    }

    /** 恢复：从活跃状态到已恢复，标记 record resolved。 */
    record Resolve(AlertStateEntity state) implements AlertTransition {
    }

    /** 无需操作：不越界且无活跃状态。 */
    record NoAction() implements AlertTransition {
    }

    /** 计数开始（逃逸窗口）：首次越界，创建计数状态行（active=false, count=1），不触发。 */
    record CountingStart(AlertRuleEntity rule) implements AlertTransition {
    }

    /** 计数递增（逃逸窗口）：持续越界但未达 confirm_count，count+1。 */
    record CountingProgress(AlertStateEntity state) implements AlertTransition {
    }

    /** 计数重置（逃逸窗口）：连续越界中断，删除计数状态行。 */
    record CountingReset(AlertStateEntity state) implements AlertTransition {
    }
}
