package com.susumonitor.server.module.command;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 命令结果回填端口：由 Agent WS handler 在收到 command.result 时调用，
 * 实现方负责 execution_id 幂等与状态迁移。
 */
public interface CommandResultHandler {

    /** 回填一次命令执行结果；未知或已终态的 execution_id 静默忽略。 */
    void complete(JsonNode payload);
}
