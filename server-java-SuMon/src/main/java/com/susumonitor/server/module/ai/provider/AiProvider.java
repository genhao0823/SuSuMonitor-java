package com.susumonitor.server.module.ai.provider;

import com.susumonitor.server.module.ai.model.AiDiagnosisContext;
import com.susumonitor.server.module.ai.vo.AiDiagnosisVo;

/** 定义供应商无关的只读诊断模型调用端口。 */
public interface AiProvider {

    /** 使用服务端固定配置和白名单上下文生成结构化诊断。 */
    AiDiagnosisVo diagnose(String question, AiDiagnosisContext context);
}
