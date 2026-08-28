package com.susumonitor.server.module.server.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/**
 * Agent Token 一次性返回对象，明文 Token 仅在注册或轮换成功响应中出现。
 *
 * @param serverId 服务器 ID
 * @param agentToken 一次性显示的明文 Token
 * @param createdAt Token 创建或轮换时间
 */
// 类级 @Schema 描述 Agent Token 一次性返回模型，供 springdoc 生成响应模型说明。
@Schema(description = "Agent Token 一次性返回结果")
public record AgentTokenVo(
        @JsonProperty("server_id") @Schema(description = "服务器 ID", minimum = "1") Long serverId,
        @JsonProperty("agent_token")
        // 描述明文 Token 字段，注明仅在注册/轮换时返回一次。
        @Schema(description = "明文 Agent Token，仅在注册或轮换响应中返回一次，"
                + "服务器查询端点永不返回", readOnly = true)
        String agentToken,
        @JsonProperty("created_at") @Schema(description = "Token 创建或轮换时间（UTC ISO-8601）")
        OffsetDateTime createdAt) {
}
