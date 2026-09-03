package com.susumonitor.server.module.ai.provider;

import com.susumonitor.server.module.ai.model.AiDiagnosisContext;
import com.susumonitor.server.module.ai.vo.AiDiagnosisVo;
import java.util.List;

/** 定义供应商无关的模型调用端口：只读诊断与命令建议共用统一出口。 */
public interface AiProvider {

    /** 使用服务端固定配置和白名单上下文生成结构化诊断。 */
    AiDiagnosisVo diagnose(String question, AiDiagnosisContext context);

    /**
     * 根据运维意图生成命令建议（仅模板 ID + 参数，1-3 条）。
     *
     * @param intent 运维意图（不可信文本）
     * @param context 白名单监控上下文
     * @param whitelistedTemplates 命令域白名单模板 ID（写入 prompt 约束模型输出）
     * @return 建议列表；模板合法性由调用方（命令域注册表）二次校验
     */
    List<CommandSuggestion> suggestCommands(String intent, AiDiagnosisContext context,
            List<String> whitelistedTemplates);
}
