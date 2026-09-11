package com.susumonitor.server.module.ai.mapper;

import com.susumonitor.server.module.ai.entity.AiUserProviderConfigEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** 访问管理员个人 AI 服务商配置（一人一行，api_key 仅密文）。 */
@Mapper
@ConditionalOnProperty(name = "susumonitor.ai.enabled", havingValue = "true")
public interface AiUserProviderConfigMapper {

    /** 按用户查询配置；不存在返回 null。 */
    AiUserProviderConfigEntity selectByUserId(@Param("userId") Long userId);

    /**
     * 按用户 upsert 配置（user_id 唯一键冲突时整体覆盖业务字段并刷新 updated_at）。
     *
     * @return 影响行数（恒为 1）
     */
    int upsert(@Param("config") AiUserProviderConfigEntity config);

    /** 删除用户配置；不存在时影响 0 行。 */
    int deleteByUserId(@Param("userId") Long userId);
}
