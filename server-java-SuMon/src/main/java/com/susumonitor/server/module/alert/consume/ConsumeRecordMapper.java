package com.susumonitor.server.module.alert.consume;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 消费幂等记录数据访问，维护消费者对事件的去重与失败留痕。
 *
 * <p>两条写入均采用 upsert（consumer+event_id 唯一键）：成功消费把已有 failed
 * 行翻转回 consumed（DLQ 重放成功的完整生命周期）；失败留痕把已有行翻转为 failed。
 * 幂等查询仅认 consumed——failed 行不阻塞 DLQ 重放重试。</p>
 */
@Mapper
public interface ConsumeRecordMapper {

    /** 成功消费 upsert：插入 consumed 行；已存在（failed/consumed）则翻转为 consumed，回写主键。 */
    int upsertConsumed(@Param("record") ConsumeRecordEntity record);

    /** 查询该消费者是否已成功消费过该事件（仅 status=consumed 视为幂等命中）。 */
    boolean existsConsumed(@Param("consumer") String consumer, @Param("eventId") String eventId);

    /** 失败留痕 upsert：插入/翻转为 failed 行并记录尝试次数与原因（消息进入 DLQ）。 */
    int upsertFailed(@Param("consumer") String consumer, @Param("eventId") String eventId,
            @Param("attempts") int attempts, @Param("lastError") String lastError);
}
