package com.susumonitor.server.module.command.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.susumonitor.server.module.command.entity.CommandRunEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** 访问 AI 命令域审计记录（V29）；MapperScan 仅扫描 @Mapper 接口。 */
@Mapper
@ConditionalOnProperty(name = "susumonitor.ai.command.enabled", havingValue = "true")
public interface CommandRunMapper {

    /** 插入 pending 审计行并回填自增主键。 */
    int insertRun(@Param("run") CommandRunEntity run);

    /** 按 id 查询运行记录。 */
    CommandRunEntity selectRunById(@Param("id") Long id);

    /** 按 execution_id 查询运行记录（result 回填入口）。 */
    CommandRunEntity selectRunByExecutionId(@Param("executionId") String executionId);

    /**
     * 分页查询运行记录，支持按服务器与状态过滤。
     *
     * <p>分页由 MyBatis-Plus 分页拦截器承担：携带 IPage 参数自动执行 COUNT
     * 并追加 LIMIT，total 回写到传入的 Page 对象。</p>
     */
    List<CommandRunEntity> selectRuns(IPage<CommandRunEntity> page, @Param("serverId") Long serverId,
            @Param("status") String status);

    /**
     * 审批 CAS：仅 pending_approval 且未过期时置 approved 并记录审批人。
     *
     * @return 影响行数；0 表示状态冲突或已过期
     */
    int approveRun(@Param("id") Long id, @Param("approverId") Long approverId,
            @Param("now") LocalDateTime now);

    /**
     * 自动审批 CAS：仅 pending_approval 时置 approved 并标记 approval_mode='auto'
     * （approver_id 保持 NULL 标识机器审批）。仅由建议服务在创建后立即调用。
     *
     * @return 影响行数；0 表示状态冲突
     */
    int autoApproveRun(@Param("id") Long id);

    /** 拒绝 CAS：仅 pending_approval 可置 rejected。 */
    int rejectRun(@Param("id") Long id, @Param("approverId") Long approverId,
            @Param("now") LocalDateTime now);

    /** 执行中 CAS：仅 approved 可置 executing。 */
    int markExecuting(@Param("id") Long id);

    /** 失败 CAS：仅 pending_approval 可置 failed（如下发时 Agent 离线）。 */
    int markFailed(@Param("id") Long id, @Param("errorCode") Integer errorCode,
            @Param("now") LocalDateTime now);

    /**
     * 结果回填：仅 approved/executing 状态可完成（Agent 迟到结果幂等丢弃）。
     *
     * @param finalStatus succeeded/failed/timeout
     */
    int completeRun(@Param("executionId") String executionId, @Param("finalStatus") String finalStatus,
            @Param("resultJson") String resultJson, @Param("exitCode") Integer exitCode,
            @Param("truncated") Boolean truncated, @Param("durationMs") Long durationMs,
            @Param("errorCode") Integer errorCode, @Param("now") LocalDateTime now);

    /** 过期扫描：查询已过期的 pending_approval 行 id（供状态 CAS 更新）。 */
    List<Long> selectExpiredIds(@Param("now") LocalDateTime now, @Param("batchSize") int batchSize);

    /** 超时扫描：查询 executing 且超过阈值未收到结果的行 id。 */
    List<Long> selectTimeoutIds(@Param("threshold") LocalDateTime threshold, @Param("batchSize") int batchSize);

    /** 按 id 批量查询运行记录（超时扫描中筛选 auto 审批行供事后通知）。 */
    List<CommandRunEntity> selectRunsByIds(@Param("ids") List<Long> ids);

    /** 按 id 批量更新状态（供过期/超时扫描使用）。 */
    int updateStatusByIds(@Param("ids") List<Long> ids, @Param("finalStatus") String finalStatus,
            @Param("errorCode") Integer errorCode, @Param("now") LocalDateTime now);

    /** 按保留期分批删除审计行。 */
    int deleteExpiredBatch(@Param("cutoffTime") LocalDateTime cutoffTime, @Param("batchSize") int batchSize);
}
