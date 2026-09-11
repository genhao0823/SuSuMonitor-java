package com.susumonitor.server.module.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.module.command.entity.CommandRunEntity;
import com.susumonitor.server.module.command.vo.CommandRunVo;
import java.time.OffsetDateTime;
import java.util.Map;

/** 实体与 VO 的映射工具（时间转 UTC OffsetDateTime、result/proposal JSON 解析）。 */
public final class CommandRunVos {

    private CommandRunVos() {
    }

    /** 实体转 VO；params/proposal/result 的 JSON 解析失败按 null 处理（审计行原文仍在库）。 */
    public static CommandRunVo toVo(CommandRunEntity entity, ObjectMapper objectMapper) {
        CommandRunVo vo = new CommandRunVo();
        vo.setId(entity.getId());
        vo.setExecutionId(entity.getExecutionId());
        vo.setServerId(entity.getServerId());
        vo.setTemplateId(entity.getTemplateId());
        vo.setParams(parseParams(entity.getParamsJson(), objectMapper));
        vo.setRenderedCommand(entity.getRenderedCommand());
        vo.setStatus(entity.getStatus());
        vo.setSource(entity.getSource());
        vo.setRiskLevel(entity.getRiskLevel());
        vo.setApprovalMode(entity.getApprovalMode());
        vo.setProposal(parseProposal(entity.getProposalJson(), objectMapper));
        vo.setResult(parseResult(entity.getResultJson(), objectMapper));
        vo.setExitCode(entity.getExitCode());
        vo.setProposerId(entity.getProposerId());
        vo.setApproverId(entity.getApproverId());
        vo.setExpiresAt(toOffset(entity.getExpiresAt()));
        vo.setCreatedAt(toOffset(entity.getCreatedAt()));
        vo.setCompletedAt(toOffset(entity.getCompletedAt()));
        return vo;
    }

    /** 解析 params_json。 */
    @SuppressWarnings("unchecked")
    private static Map<String, String> parseParams(String json, ObjectMapper objectMapper) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception exception) {
            return null;
        }
    }

    /** 解析 proposal_json 为建议元数据。 */
    private static CommandRunVo.Proposal parseProposal(String json, ObjectMapper objectMapper) {
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            CommandRunVo.Proposal proposal = new CommandRunVo.Proposal();
            proposal.setReason(node.path("reason").asText(null));
            proposal.setModel(node.path("model").asText(null));
            proposal.setPromptVersion(node.path("prompt_version").asText(null));
            return proposal;
        } catch (Exception exception) {
            return null;
        }
    }

    /** 解析 result_json 为结构化执行结果。 */
    private static CommandRunVo.Result parseResult(String json, ObjectMapper objectMapper) {
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            CommandRunVo.Result result = new CommandRunVo.Result();
            result.setStdout(node.path("stdout").asText(null));
            result.setStderr(node.path("stderr").asText(null));
            result.setTruncated(node.path("truncated").asBoolean(false));
            result.setError(node.path("error").asText(null));
            return result;
        } catch (Exception exception) {
            return null;
        }
    }

    /** UTC 时间转换；null 透传。 */
    private static OffsetDateTime toOffset(java.time.LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffsetHolder.UTC);
    }

    /** UTC 偏移常量持有者。 */
    private static final class ZoneOffsetHolder {
        private static final java.time.ZoneOffset UTC = java.time.ZoneOffset.UTC;
    }
}
