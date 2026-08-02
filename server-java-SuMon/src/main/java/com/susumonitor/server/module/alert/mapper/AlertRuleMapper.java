package com.susumonitor.server.module.alert.mapper;

import com.susumonitor.server.module.alert.entity.AlertRuleEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 告警规则 Mapper，提供规则 CRUD 和按服务器查询启用规则。
 *
 * <p>所有查询过滤 deleted=0，软删除规则不参与评估和查询。</p>
 */
@Mapper
public interface AlertRuleMapper {

    /** 插入新规则。 */
    int insertRule(@Param("rule") AlertRuleEntity rule);

    /** 根据主键查询单条未删除规则。 */
    AlertRuleEntity selectActiveRuleById(@Param("id") Long id);

    /** 查询所有未删除规则，按 created_at DESC 排序。 */
    List<AlertRuleEntity> selectActiveRules();

    /** 查询指定服务器匹配的已启用规则，含通用规则（server_id IS NULL）。 */
    List<AlertRuleEntity> selectEnabledRulesForServer(@Param("serverId") Long serverId);

    /** 更新规则阈值、等级、启用状态和确认次数（confirmCount 为 null 时保持原值）。 */
    int updateRule(@Param("id") Long id, @Param("thresholdValue") java.math.BigDecimal thresholdValue,
            @Param("level") String level, @Param("enabled") Boolean enabled,
            @Param("confirmCount") Integer confirmCount);

    /** 软删除规则，标记 deleted=1 并记录删除时间。 */
    int softDeleteRule(@Param("id") Long id, @Param("deletedAt") LocalDateTime deletedAt);

    /** 查询同签名活跃规则，excludeId 用于更新时排除自身。 */
    boolean existsActiveRule(
            @Param("serverId") Long serverId,
            @Param("metric") String metric,
            @Param("operator") String operator,
            @Param("thresholdValue") java.math.BigDecimal thresholdValue,
            @Param("level") String level,
            @Param("excludeId") Long excludeId);
}
