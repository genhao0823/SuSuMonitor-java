package com.susumonitor.server.module.command;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.common.limit.FixedWindowRateLimiter;
import com.susumonitor.server.common.vo.PageResult;
import com.susumonitor.server.config.AppProperties;
import com.susumonitor.server.module.command.entity.CommandRunEntity;
import com.susumonitor.server.module.command.mapper.CommandRunMapper;
import com.susumonitor.server.module.server.service.ServerService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 命令域 M1 审批制状态机：创建待审批运行 → 审批/拒绝 → 经 Agent 通道下发 →
 * 结果回填 → 过期/超时扫描。
 *
 * <p>安全要点：模板外命令在渲染前被注册表拒绝；审批与下发均以数据库 CAS
 * 驱动；Agent 结果以 execution_id 幂等回填（迟到结果对终态行无效果）；
 * 下发失败立即置 failed 并向调用方返回稳定错误。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "susumonitor.ai.command.enabled", havingValue = "true")
public class CommandRunService implements CommandResultHandler {

    /** 运行状态枚举值（与 V29 status 列一致）。 */
    public static final String STATUS_PENDING = "pending_approval";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_EXECUTING = "executing";
    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_REJECTED = "rejected";
    public static final String STATUS_EXPIRED = "expired";
    public static final String STATUS_TIMEOUT = "timeout";

    /** 来源：AI 建议或管理员手动创建。 */
    public static final String SOURCE_AI = "ai";
    public static final String SOURCE_MANUAL = "manual";

    /** 审批方式：人工或策略自动（自动时 approver_id 保持 NULL）。 */
    public static final String APPROVAL_MODE_MANUAL = "manual";
    public static final String APPROVAL_MODE_AUTO = "auto";

    private final CommandRunMapper runMapper;
    private final CommandTemplateRegistry templateRegistry;
    private final CommandTransport transport;
    private final ServerService serverService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final FixedWindowRateLimiter rateLimiter;
    private final ObjectProvider<CommandAutoApprovalNotifier> autoApprovalNotifier;

    /** 注入审计 Mapper、模板注册表、出站端口与治理组件。 */
    public CommandRunService(CommandRunMapper runMapper, CommandTemplateRegistry templateRegistry,
            CommandTransport transport, ServerService serverService, AppProperties appProperties,
            ObjectMapper objectMapper, Clock clock,
            ObjectProvider<CommandAutoApprovalNotifier> autoApprovalNotifier) {
        this.runMapper = runMapper;
        this.templateRegistry = templateRegistry;
        this.transport = transport;
        this.serverService = serverService;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.autoApprovalNotifier = autoApprovalNotifier;
        this.rateLimiter = new FixedWindowRateLimiter(
                appProperties.getAi().getCommand().getRateLimitMaxRequests(),
                Duration.ofSeconds(appProperties.getAi().getCommand().getRateLimitWindowSeconds()), clock);
    }

    /**
     * 创建待审批运行（AI 建议或手动），返回含人工预览命令的审计行。
     */
    public CommandRunEntity createPendingRun(Long proposerId, Long serverId, String templateId,
            Map<String, String> params, String source, String proposalJson) {
        checkRateLimit(proposerId);
        if (serverId == null || serverId <= 0 || !serverService.existsActive(serverId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        String rendered = templateRegistry.validateAndRender(templateId, params);
        CommandTemplateRegistry.Template template = templateRegistry.find(templateId);
        CommandRunEntity run = new CommandRunEntity();
        run.setExecutionId(UUID.randomUUID().toString());
        run.setRequestId(MDC.get("request_id"));
        run.setProposerId(proposerId);
        run.setServerId(serverId);
        run.setTemplateId(templateId);
        run.setParamsJson(writeParams(params));
        run.setParamsHash(sha256(run.getParamsJson()));
        run.setRenderedCommand(rendered);
        run.setStatus(STATUS_PENDING);
        run.setSource(source);
        run.setRiskLevel(template.risk().value());
        run.setApprovalMode(APPROVAL_MODE_MANUAL);
        run.setProposalJson(proposalJson);
        LocalDateTime now = LocalDateTime.now(clock);
        run.setCreatedAt(now);
        run.setExpiresAt(now.plusMinutes(appProperties.getAi().getCommand().getApprovalExpireMinutes()));
        if (runMapper.insertRun(run) != 1) {
            throw new BusinessException(ErrorCode.DATABASE_ERROR);
        }
        log.info("command run created, id={}, executionId={}, template={}, server={}, source={}",
                run.getId(), run.getExecutionId(), templateId, serverId, source);
        return run;
    }

    /**
     * 审批并立即下发；返回最新运行状态（approved 或已进入 executing/succeeded）。
     *
     * <p>下发失败时把运行置 failed(40906) 并向调用方抛出同一错误；
     * 过期的 pending 先被懒置 expired，审批统一返回状态冲突。</p>
     */
    public CommandRunEntity approve(Long runId, Long approverId) {
        CommandRunEntity run = requireRun(runId);
        LocalDateTime now = LocalDateTime.now(clock);
        expireIfNeeded(run, now);
        if (runMapper.approveRun(runId, approverId, now) != 1) {
            throw new BusinessException(ErrorCode.COMMAND_RUN_STATE_CONFLICT);
        }
        if (!dispatch(run)) {
            runMapper.markFailed(runId, ErrorCode.COMMAND_AGENT_OFFLINE.getCode(), LocalDateTime.now(clock));
            throw new BusinessException(ErrorCode.COMMAND_AGENT_OFFLINE);
        }
        runMapper.markExecuting(runId);
        return requireRun(runId);
    }

    /** 拒绝待审批运行。 */
    public CommandRunEntity reject(Long runId, Long approverId) {
        CommandRunEntity run = requireRun(runId);
        LocalDateTime now = LocalDateTime.now(clock);
        expireIfNeeded(run, now);
        if (runMapper.rejectRun(runId, approverId, now) != 1) {
            throw new BusinessException(ErrorCode.COMMAND_RUN_STATE_CONFLICT);
        }
        return requireRun(runId);
    }

    /**
     * 策略自动审批并立即下发；仅由建议服务在创建后立即调用（无对应 REST 路由）。
     *
     * <p>与人工 approve 的差异：不写 approver_id（机器审批以 approval_mode='auto'
     * 标识）；刚创建的行无需过期检查，CAS 仅以 pending_approval 状态为准。
     * 下发失败同样置 failed(40906) 并向调用方抛出稳定错误。</p>
     */
    public CommandRunEntity autoApprove(Long runId) {
        CommandRunEntity run = requireRun(runId);
        if (runMapper.autoApproveRun(runId) != 1) {
            throw new BusinessException(ErrorCode.COMMAND_RUN_STATE_CONFLICT);
        }
        if (!dispatch(run)) {
            runMapper.markFailed(runId, ErrorCode.COMMAND_AGENT_OFFLINE.getCode(), LocalDateTime.now(clock));
            throw new BusinessException(ErrorCode.COMMAND_AGENT_OFFLINE);
        }
        runMapper.markExecuting(runId);
        return requireRun(runId);
    }

    /**
     * Agent 结果回填入口（由 WS handler 调用）：execution_id 幂等，终态行忽略迟到结果。
     */
    @Override
    public void complete(JsonNode payload) {
        String executionId = payload.path("execution_id").asText(null);
        if (executionId == null || executionId.isBlank()) {
            log.warn("command.result ignored: missing execution_id");
            return;
        }
        CommandRunEntity run = runMapper.selectRunByExecutionId(executionId);
        if (run == null || !(STATUS_EXECUTING.equals(run.getStatus()) || STATUS_APPROVED.equals(run.getStatus()))) {
            log.warn("command.result ignored: unknown or settled execution_id={}", executionId);
            return;
        }
        boolean success = payload.path("success").asBoolean(false);
        String agentError = payload.path("error").asText(null);
        String finalStatus = success ? STATUS_SUCCEEDED
                : ("timeout".equals(agentError) ? STATUS_TIMEOUT : STATUS_FAILED);
        String resultJson = buildResultJson(payload);
        runMapper.completeRun(executionId, finalStatus, resultJson,
                payload.path("exit_code").asInt(0), payload.path("truncated").asBoolean(false),
                payload.path("duration_ms").asLong(0), null, LocalDateTime.now(clock));
        log.info("command result stored, executionId={}, finalStatus={}, exitCode={}",
                executionId, finalStatus, payload.path("exit_code").asInt(0));
        // 自动审批的事后通知（M2 收口）：终态回填后尽力而为推送；人工审批不推送。
        // 用回填值补齐内存实体，避免为通知再查一次库。
        run.setStatus(finalStatus);
        run.setExitCode(payload.path("exit_code").asInt(0));
        run.setDurationMs(payload.path("duration_ms").asLong(0));
        notifyAutoApprovals(List.of(run), finalStatus);
    }

    /** 过期/超时双扫描：把到期 pending 置 expired、超过执行时限的 executing 置 timeout。 */
    public void sweep() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> expired = runMapper.selectExpiredIds(now, 500);
        if (!expired.isEmpty()) {
            runMapper.updateStatusByIds(expired, STATUS_EXPIRED, null, now);
            log.info("command runs expired, count={}", expired.size());
        }
        LocalDateTime threshold = now.minusSeconds(appProperties.getAi().getCommand().getExecutionTimeoutSeconds() + 30);
        List<Long> timedOut = runMapper.selectTimeoutIds(threshold, 500);
        if (!timedOut.isEmpty()) {
            // 置 timeout 前先取行：自动审批运行需要事后通知，人工运行不推送。
            List<CommandRunEntity> timedOutRuns = runMapper.selectRunsByIds(timedOut);
            runMapper.updateStatusByIds(timedOut, STATUS_TIMEOUT,
                    ErrorCode.COMMAND_EXECUTION_TIMEOUT.getCode(), now);
            log.info("command runs timed out, count={}", timedOut.size());
            notifyAutoApprovals(timedOutRuns, STATUS_TIMEOUT);
        }
    }

    /** 对 auto 审批的运行逐条推送结果通知；通知器缺席（理论上不可能，同开关装配）时静默跳过。 */
    private void notifyAutoApprovals(List<CommandRunEntity> runs, String finalStatus) {
        CommandAutoApprovalNotifier notification = autoApprovalNotifier.getIfAvailable();
        if (notification == null) {
            return;
        }
        for (CommandRunEntity run : runs) {
            if (run == null || !APPROVAL_MODE_AUTO.equals(run.getApprovalMode())) {
                continue;
            }
            try {
                notification.notifyCompletion(run, finalStatus);
            } catch (RuntimeException exception) {
                log.warn("command auto-approval notification failed unexpectedly, runId={}", run.getId(),
                        exception);
            }
        }
    }    /** 查询单条运行记录；不存在返回 40404。 */
    public CommandRunEntity get(Long runId) {
        return requireRun(runId);
    }

    /** 分页查询运行记录。 */
    public PageResult<CommandRunEntity> list(Long serverId, String status, Integer page, Integer pageSize) {
        if (page == null || page < 1 || pageSize == null || pageSize < 1 || pageSize > 100
                || (status != null && !isValidStatus(status))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        // 分页由 MyBatis-Plus 拦截器承担：COUNT 自动执行，total 回写 Page。
        Page<CommandRunEntity> pager = new Page<>(page, pageSize);
        List<CommandRunEntity> items = runMapper.selectRuns(pager, serverId, status);
        PageResult<CommandRunEntity> result = new PageResult<>();
        result.setItems(items);
        result.setTotal(pager.getTotal());
        result.setPage(page);
        result.setPageSize(pageSize);
        return result;
    }

    /** 校验状态过滤参数属于已知枚举。 */
    private boolean isValidStatus(String status) {
        return STATUS_PENDING.equals(status) || STATUS_APPROVED.equals(status)
                || STATUS_EXECUTING.equals(status) || STATUS_SUCCEEDED.equals(status)
                || STATUS_FAILED.equals(status) || STATUS_REJECTED.equals(status)
                || STATUS_EXPIRED.equals(status) || STATUS_TIMEOUT.equals(status);
    }

    /** 下发 command.execute 帧；JSON 构造失败视为执行错误（不会发生：字段均受控）。 */
    private boolean dispatch(CommandRunEntity run) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("server_id", run.getServerId());
        payload.put("execution_id", run.getExecutionId());
        payload.put("template", run.getTemplateId());
        try {
            payload.putPOJO("params", objectMapper.readTree(run.getParamsJson()));
        } catch (JsonProcessingException exception) {
            log.error("stored params_json is no longer parseable, executionId={}", run.getExecutionId());
            return false;
        }
        payload.put("timeout_seconds", appProperties.getAi().getCommand().getExecutionTimeoutSeconds());
        String body = objectMapper.createObjectNode()
                .put("type", "command.execute")
                .put("timestamp", java.time.OffsetDateTime.now(clock).toString())
                .set("payload", payload).toString();
        return transport.send(run.getServerId(), body);
    }

    /** 懒过期：pending 且已过期的运行先 CAS 置 expired（供 approve/reject 拒绝路径）。 */
    private void expireIfNeeded(CommandRunEntity run, LocalDateTime now) {
        if (STATUS_PENDING.equals(run.getStatus()) && run.getExpiresAt() != null
                && !run.getExpiresAt().isAfter(now)) {
            runMapper.updateStatusByIds(List.of(run.getId()), STATUS_EXPIRED, null, now);
        }
    }

    /** 查询或 40404。 */
    private CommandRunEntity requireRun(Long runId) {
        if (runId == null || runId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        CommandRunEntity run = runMapper.selectRunById(runId);
        if (run == null) {
            throw new BusinessException(ErrorCode.COMMAND_RUN_NOT_FOUND);
        }
        return run;
    }

    /** 按管理员固定窗口限流（M1 单 JVM 内存实现；Redis 版列为开放项）。 */
    private void checkRateLimit(Long actorId) {
        if (!rateLimiter.tryAcquire(String.valueOf(actorId))) {
            throw new BusinessException(ErrorCode.COMMAND_RATE_LIMIT_REACHED);
        }
    }

    /** 序列化参数为 JSON 字符串；类型化入口保证只含字符串键值。 */
    private String writeParams(Map<String, String> params) {
        try {
            return objectMapper.writeValueAsString(params == null ? Map.of() : params);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.COMMAND_PARAM_INVALID, exception);
        }
    }

    /** 构建落库的 result_json：stdout/stderr 双重截断（Go 已截断，这里兜底）。 */
    private String buildResultJson(JsonNode payload) {
        int max = appProperties.getAi().getCommand().getResultMaxBytes();
        ObjectNode node = objectMapper.createObjectNode();
        node.put("stdout", truncate(payload.path("stdout").asText(""), max));
        node.put("stderr", truncate(payload.path("stderr").asText(""), max));
        node.put("truncated", payload.path("truncated").asBoolean(false));
        String agentError = payload.path("error").asText(null);
        if (agentError != null) {
            node.put("error", agentError);
        }
        return node.toString();
    }

    /** 按字节上限截断字符串（UTF-8 安全边界：回退到字符截断避免异常）。 */
    private String truncate(String value, int maxBytes) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return value;
        }
        String candidate = new String(bytes, 0, maxBytes, StandardCharsets.UTF_8);
        return candidate;
    }

    /** 参数 SHA-256（审计完整性校验用）。 */
    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) {
                result.append(String.format(Locale.ROOT, "%02x", item));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
