package com.susumonitor.server.module.command;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import java.util.Locale;

/**
 * 命令模板风险等级（command-protocol-v1.md §模板表 的等级列）。
 *
 * <p>low=只读诊断（M1 白名单全部属于此级）；medium=低影响变更（M2 预留）；
 * high=高影响变更（永远需要人工审批，自动审批策略不可覆盖）。阈值比较按
 * ordinal 进行：模板等级 ordinal &lt;= 策略阈值 ordinal 才允许自动审批。</p>
 */
public enum CommandRiskLevel {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    private final String value;

    CommandRiskLevel(String value) {
        this.value = value;
    }

    /** 存储与 API 输出值。 */
    public String value() {
        return value;
    }

    /** 解析存储/入参值；非法值抛 INVALID_REQUEST_PARAMETER（fail-closed）。 */
    public static CommandRiskLevel parse(String value) {
        if (value == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (CommandRiskLevel level : values()) {
            if (level.value.equals(normalized)) {
                return level;
            }
        }
        throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
    }

    /** 自动审批阈值合法性：high 不可作为阈值（等价于禁止全部自动审批，语义上应直接关闭开关）。 */
    public boolean isAllowedThreshold() {
        return this != HIGH;
    }
}
