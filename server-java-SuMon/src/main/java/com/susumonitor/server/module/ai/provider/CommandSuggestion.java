package com.susumonitor.server.module.ai.provider;

import com.susumonitor.server.module.ai.model.AiDiagnosisContext;
import java.util.Map;

/** AI 给出的一条命令建议：仅允许白名单模板 ID + 类型化参数（不含任意命令字符串）。 */
public record CommandSuggestion(String templateId, Map<String, String> params, String reason) {
}
