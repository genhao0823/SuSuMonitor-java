package com.susumonitor.server.module.command.mapper;

import com.susumonitor.server.module.command.entity.CommandAutoApprovalPolicyEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** 访问 AI 命令域自动审批策略（V33 单行表）；MapperScan 仅扫描 @Mapper 接口。 */
@Mapper
@ConditionalOnProperty(name = "susumonitor.ai.command.enabled", havingValue = "true")
public interface CommandAutoApprovalPolicyMapper {

    /** 读取单行策略；行缺失返回 null（服务层按禁用处理，fail-closed）。 */
    CommandAutoApprovalPolicyEntity selectPolicy(@Param("id") Integer id);

    /** upsert 单行策略（INSERT ... ON DUPLICATE KEY UPDATE）；时间由调用方注入便于测试。 */
    int upsertPolicy(@Param("enabled") Boolean enabled, @Param("maxRiskLevel") String maxRiskLevel,
            @Param("updatedBy") Long updatedBy, @Param("now") LocalDateTime now);
}
