package com.susumonitor.server.module.alert.mapper;

import com.susumonitor.server.module.alert.entity.AlertStateEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 告警状态 Mapper，维护每条规则在每台服务器上的当前越界状态。
 *
 * <p>乐观锁通过 SQL WHERE version = #{version} 手动处理，
 * 不使用 MyBatis-Plus 的 @Version 注解，因为项目使用 XML Mapper。</p>
 */
@Mapper
public interface AlertStateMapper {

    /**
     * 一次查询某服务器的全部状态行（S2 性能优化：按 server 批量查状态，
     * 消除每条规则一次 SQL 往返；全局规则的状态行同样按上报 server 落库，一并覆盖）。
     */
    List<AlertStateEntity> selectByServerId(@Param("serverId") Long serverId);

    /** 插入新状态行，回写主键。 */
    int insertState(@Param("state") AlertStateEntity state);

    /** 计数递增（逃逸窗口）：连续越界 +1，未触发阶段。 */
    int incrementBreachCount(@Param("id") Long id, @Param("lastTriggeredAt") LocalDateTime lastTriggeredAt,
            @Param("version") int version);

    /** 计数达到 confirm_count，把状态行升级为活跃并绑定告警记录。 */
    int activateOnBreachThreshold(@Param("id") Long id, @Param("alertRecordId") Long alertRecordId,
            @Param("lastTriggeredAt") LocalDateTime lastTriggeredAt, @Param("version") int version);

    /** 更新状态为活跃，设置 alert_record_id 和 last_triggered_at，version + 1。 */
    int updateStateActive(@Param("id") Long id, @Param("alertRecordId") Long alertRecordId,
            @Param("lastTriggeredAt") LocalDateTime lastTriggeredAt, @Param("version") int version);

    /**
     * 恢复后删除状态行（乐观锁防误删）。
     *
     * <p>状态机约定恢复后的 state 为 null（见 {@code AlertStateMachine} javadoc），
     * 因此恢复语义是删除行而非置 active=0，否则恢复后再次越界无法重新触发。</p>
     */
    int deleteState(@Param("id") Long id, @Param("version") int version);
}
