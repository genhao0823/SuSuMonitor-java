package com.susumonitor.server.module.server.mapper;

import com.susumonitor.server.module.server.entity.SshTestHistoryEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * SSH 连接测试历史 Mapper，支撑测试结果留痕与最近记录查询。
 */
@Mapper
public interface SshTestHistoryMapper {

    /** 插入一条测试历史记录，回写主键。 */
    int insert(@Param("entity") SshTestHistoryEntity entity);

    /** 查询某服务器最近的测试历史，按测试时间倒序。 */
    List<SshTestHistoryEntity> selectRecentByServerId(@Param("serverId") Long serverId,
            @Param("limit") int limit);
}
