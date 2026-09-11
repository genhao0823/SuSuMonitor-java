package com.susumonitor.server.module.command;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.module.command.entity.CommandAutoApprovalPolicyEntity;
import com.susumonitor.server.module.command.mapper.CommandAutoApprovalPolicyMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * AI 命令域自动审批策略：实例级单行配置（V33），控制"中低风险 AI 建议免人工审批"。
 *
 * <p>安全语义：fail-closed —— 策略行缺失或读取异常一律视为禁用（与关闭开关等价）；
 * 仅 AI 来源命令适用，high 风险模板永远不允许自动审批（阈值本身禁止取 high）；
 * 实例级总熔断仍是 susumonitor.ai.command.enabled。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "susumonitor.ai.command.enabled", havingValue = "true")
public class CommandAutoApprovalPolicyService {

    /** 单行策略固定主键。 */
    public static final int POLICY_ID = 1;

    private final CommandAutoApprovalPolicyMapper policyMapper;
    private final Clock clock;

    public CommandAutoApprovalPolicyService(CommandAutoApprovalPolicyMapper policyMapper, Clock clock) {
        this.policyMapper = policyMapper;
        this.clock = clock;
    }

    /** 策略快照（对 API 输出与内部判定共用）；行缺失时返回禁用默认值。 */
    public Snapshot get() {
        CommandAutoApprovalPolicyEntity entity;
        try {
            entity = policyMapper.selectPolicy(POLICY_ID);
        } catch (RuntimeException exception) {
            // fail-closed：读库异常按禁用处理，命令域其余功能不受影响。
            log.warn("auto approval policy read failed, fallback to disabled", exception);
            return Snapshot.disabled();
        }
        if (entity == null || entity.getEnabled() == null) {
            return Snapshot.disabled();
        }
        CommandRiskLevel threshold;
        try {
            threshold = CommandRiskLevel.parse(entity.getMaxRiskLevel());
        } catch (BusinessException exception) {
            log.warn("auto approval policy has invalid threshold {}, fallback to disabled",
                    entity.getMaxRiskLevel());
            return Snapshot.disabled();
        }
        return new Snapshot(true, entity.getEnabled(), threshold,
                toOffset(entity.getUpdatedAt()), entity.getUpdatedBy());
    }

    /** 更新策略：阈值校验（low/medium，high 拒绝）后 upsert 并返回最新快照。 */
    public Snapshot update(Boolean enabled, String maxRiskLevel, Long operatorId) {
        if (enabled == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        CommandRiskLevel threshold = CommandRiskLevel.parse(maxRiskLevel);
        if (!threshold.isAllowedThreshold()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        policyMapper.upsertPolicy(enabled, threshold.value(), operatorId, LocalDateTime.now(clock));
        log.info("auto approval policy updated, enabled={}, maxRiskLevel={}, operatorId={}",
                enabled, threshold.value(), operatorId);
        return get();
    }

    /**
     * 判定模板是否允许自动审批：开关开启且模板风险不超过阈值。
     *
     * <p>high 永不放行（即便阈值逻辑未来被放宽，这里也按 ordinal 硬比较）。</p>
     */
    public boolean allows(CommandTemplateRegistry.Template template) {
        Snapshot snapshot = get();
        return snapshot.enabled() && template.risk() != CommandRiskLevel.HIGH
                && template.risk().ordinal() <= snapshot.maxRiskLevel().ordinal();
    }

    /** 策略只读快照；present=false 表示策略行缺失（视为禁用）。 */
    public record Snapshot(boolean present, boolean enabled, CommandRiskLevel maxRiskLevel,
            OffsetDateTime updatedAt, Long updatedBy) {

        static Snapshot disabled() {
            return new Snapshot(false, false, CommandRiskLevel.LOW, null, null);
        }
    }

    private static OffsetDateTime toOffset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
