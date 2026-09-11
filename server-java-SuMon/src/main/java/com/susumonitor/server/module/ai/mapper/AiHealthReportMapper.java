package com.susumonitor.server.module.ai.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.susumonitor.server.module.ai.entity.AiHealthReportEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 访问 AI 定时健康报告存储与只读聚合数据源（F3）。
 *
 * <p>随 {@code susumonitor.ai.report.enabled} 开关装配：关闭时不创建代理，
 * 依赖本 Mapper 的调度器/端点必须使用相同的条件装配，避免启动失败。
 * 聚合查询返回 Map（snake_case 别名），由服务层转换为白名单事实 record。</p>
 */
@Mapper
@ConditionalOnProperty(name = "susumonitor.ai.report.enabled", havingValue = "true")
public interface AiHealthReportMapper {

    /** 按自然日 UPSERT 一份报告；同日重复生成覆盖旧行。 */
    int upsertReport(@Param("report") AiHealthReportEntity report);

    AiHealthReportEntity selectByDate(@Param("reportDate") LocalDate reportDate);

    AiHealthReportEntity selectById(@Param("id") Long id);

    /** 分页查询历史报告，按 report_date 倒序；分页由 MyBatis-Plus 拦截器承担。 */
    List<AiHealthReportEntity> selectReports(IPage<AiHealthReportEntity> page);

    /** 统计时间窗口内已落库报告的 token 总量，供按天预算校验使用。 */
    Long sumTotalTokensBetween(@Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    int deleteExpiredBatch(@Param("cutoffTime") LocalDateTime cutoffTime, @Param("batchSize") int batchSize);

    /** 服务器清单快照：total_count / online_count（软删排除）。 */
    Map<String, Object> selectServerInventory();

    /** 当前离线服务器清单（status != online），按最后心跳升序，最多 limit 条。 */
    List<Map<String, Object>> selectOfflineServers(@Param("limit") int limit);

    /** 报告窗口内全量服务器指标均值与峰值：avg/max × cpu/memory/disk。 */
    Map<String, Object> selectMetricAggregates(@Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /** 报告窗口内 cpu 峰值所属服务器 ID；窗口无数据返回 null。 */
    Long selectMaxCpuServerId(@Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /** 报告窗口内内存峰值所属服务器 ID；窗口无数据返回 null。 */
    Long selectMaxMemoryServerId(@Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /** 报告窗口内磁盘峰值所属服务器 ID；窗口无数据返回 null。 */
    Long selectMaxDiskServerId(@Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /** 报告窗口内告警统计：触发总数、分级计数与恢复计数。 */
    Map<String, Object> selectAlertStatistics(@Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /** 报告窗口内告警最多的服务器（软删排除），按告警数倒序，最多 limit 条。 */
    List<Map<String, Object>> selectTopAlertServers(@Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime, @Param("limit") int limit);
}
