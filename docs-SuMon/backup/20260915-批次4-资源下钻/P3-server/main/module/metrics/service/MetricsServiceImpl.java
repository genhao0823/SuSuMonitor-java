package com.susumonitor.server.module.metrics.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.common.vo.PageResult;
import com.susumonitor.server.module.metrics.dto.MetricsReportPayload;
import com.susumonitor.server.module.metrics.dto.ProcessSamplePayload;
import com.susumonitor.server.module.metrics.entity.MetricsEntity;
import com.susumonitor.server.module.metrics.entity.MetricsIngestionEntity;
import com.susumonitor.server.module.metrics.mapper.MetricsMapper;
import com.susumonitor.server.module.metrics.outbox.OutboxEnvelopeFactory;
import com.susumonitor.server.module.metrics.outbox.OutboxService;
import com.susumonitor.server.module.metrics.vo.MetricsHistoryVo;
import com.susumonitor.server.module.metrics.vo.MetricsLatestVo;
import com.susumonitor.server.module.metrics.vo.ProcessSampleVo;
import com.susumonitor.server.module.metrics.vo.ProcessSnapshotVo;
import com.susumonitor.server.module.server.service.ServerService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 负责固定宽表指标校验、写入、最新值和历史分页查询。 */
@Service
public class MetricsServiceImpl implements MetricsService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_HISTORY_DAYS = 7;
    // Top 进程列表条数上限，与协议（websocket-protocol.md v1.4）一致。
    private static final int MAX_PROCESS_ENTRIES = 20;
    // 进程名字符数上限，与协议一致。
    private static final int MAX_PROCESS_NAME_LENGTH = 128;
    private static final ZoneId APPLICATION_ZONE = ZoneOffset.UTC;
    private final MetricsMapper metricsMapper;
    private final ServerService serverService;
    private final OutboxService outboxService;
    private final OutboxEnvelopeFactory outboxEnvelopeFactory;
    private final ApplicationEventPublisher eventPublisher;
    private final ProcessSnapshotRegistry processSnapshotRegistry;

    /** 注入指标数据访问组件、服务器契约、Outbox 登记服务、信封工厂与进程快照注册表（servers 表访问统一走 ServerService）。 */
    public MetricsServiceImpl(MetricsMapper metricsMapper, ServerService serverService,
            OutboxService outboxService, OutboxEnvelopeFactory outboxEnvelopeFactory,
            ApplicationEventPublisher eventPublisher, ProcessSnapshotRegistry processSnapshotRegistry) {
        this.metricsMapper = metricsMapper;
        this.serverService = serverService;
        this.outboxService = outboxService;
        this.outboxEnvelopeFactory = outboxEnvelopeFactory;
        this.eventPublisher = eventPublisher;
        this.processSnapshotRegistry = processSnapshotRegistry;
    }

    /**
     * 写入已认证 Agent 上报的一条完整宽表快照。
     *
     * <p>同一服务器的写入先锁定服务器行，确保重复投递与采样时间判定串行化。
     * 同一 messageId 重复投递静默成功；采样时间不严格晚于最后接受采样时拒绝，
     * 因而不会触发重复 Metrics 事件、告警评估或 WebSocket 推送。</p>
     *
     * <p>指标入库成功后，与指标同事务登记 Outbox 待发布事件（MVP-10）：
     * 事务回滚时 outbox 行一并回滚，保证"已入库指标必有待发布事件"。</p>
     *
     * <p>入库阶段的可恢复数据库故障（连接/锁等）包装为
     * {@link MetricsRejectionReason#RETRIABLE_SERVER_ERROR}，Agent 有限重试后死信；
     * 永久拒绝（载荷/采样时间/服务器不存在）维持原语义。</p>
     */
    @Transactional
    public void report(Long authenticatedServerId, String messageId, MetricsReportPayload payload) {
        validatePayload(authenticatedServerId, messageId, payload);
        try {
            if (!serverService.existsActiveForUpdate(authenticatedServerId)) {
                throw new MetricsRejectedException(MetricsRejectionReason.SERVER_NOT_FOUND);
            }
            if (isDuplicateIngestion(authenticatedServerId, messageId, payload.getCollectedAt())) {
                return;
            }
            MetricsEntity entity = toEntity(payload);
            LocalDateTime latestCollectedAt = metricsMapper.selectLatestCollectedAt(authenticatedServerId);
            if (latestCollectedAt != null && !entity.getCollectedAt().isAfter(latestCollectedAt)) {
                throw new MetricsRejectedException(MetricsRejectionReason.STALE_COLLECTED_AT);
            }
            if (metricsMapper.insertMetric(entity) != 1) {
                throw new BusinessException(ErrorCode.DATABASE_ERROR);
            }
            // 与指标同事务登记 Outbox 待发布事件：eventId 由本处生成并写入信封，
            // 保证行内 event_id 与 payload 中 event_id 一致（消费侧幂等主键）。
            String eventId = UUID.randomUUID().toString();
            String envelope = outboxEnvelopeFactory.build(entity, messageId, eventId);
            outboxService.enqueue(OutboxEnvelopeFactory.EVENT_TYPE, OutboxEnvelopeFactory.ROUTING_KEY,
                    envelope, eventId);
            eventPublisher.publishEvent(new MetricsReportedEvent(toLatestVo(entity), toProcessSnapshot(payload)));
        } catch (DuplicateKeyException exception) {
            // 重复投递竞态（isDuplicateIngestion 预检查兜底）：数据级冲突，重发不会改变结果
            throw exception;
        } catch (DataAccessException exception) {
            // 可恢复入库故障 → retriable nack，Agent 有限重试后仍未成功再死信
            throw new MetricsRejectedException(MetricsRejectionReason.RETRIABLE_SERVER_ERROR);
        }
    }

    /** 查询服务器最新指标；无记录时返回资源不存在。 */
    @Transactional(readOnly = true)
    public MetricsLatestVo latest(Long serverId) {
        ensureServerExists(serverId);
        MetricsEntity entity = metricsMapper.selectLatestByServerId(serverId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return toLatestVo(entity);
    }

    /** 查询服务器新鲜窗口内的实时 Top 进程快照；无快照或已过期时为空。 */
    @Transactional(readOnly = true)
    public Optional<ProcessSnapshotVo> latestProcessSnapshot(Long serverId) {
        ensureServerExists(serverId);
        return processSnapshotRegistry.latest(serverId);
    }

    /** 查询服务器时间窗口内的历史指标。 */
    @Transactional(readOnly = true)
    public PageResult<MetricsHistoryVo> history(Long serverId, OffsetDateTime startTime,
            OffsetDateTime endTime, Integer page, Integer pageSize) {
        ensureServerExists(serverId);
        if (page == null || page < 1 || pageSize == null || pageSize < 1 || pageSize > MAX_PAGE_SIZE
                || startTime == null || endTime == null || endTime.isBefore(startTime)
                || endTime.isAfter(startTime.plusDays(MAX_HISTORY_DAYS))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        LocalDateTime start = startTime.atZoneSameInstant(APPLICATION_ZONE).toLocalDateTime();
        LocalDateTime end = endTime.atZoneSameInstant(APPLICATION_ZONE).toLocalDateTime();
        // 分页由 MyBatis-Plus 拦截器承担：COUNT 自动执行，total 回写 Page。
        Page<MetricsEntity> pager = new Page<>(page, pageSize);
        List<MetricsHistoryVo> items = metricsMapper.selectHistory(pager, serverId, start, end)
                .stream().map(this::toHistoryVo).toList();
        PageResult<MetricsHistoryVo> result = new PageResult<>();
        result.setItems(items);
        result.setTotal(pager.getTotal());
        result.setPage(page);
        result.setPageSize(pageSize);
        return result;
    }

    /**
     * 验证服务器存在且有效，不存在时抛出资源不存在异常。
     *
     * @param serverId 服务器 ID
     */
    private void ensureServerExists(Long serverId) {
        if (serverId == null || serverId <= 0 || !serverService.existsActive(serverId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    /** 将唯一消息 ID 登记为已接收；唯一键冲突代表 Agent 对同一消息的合法重试。 */
    private boolean isDuplicateIngestion(Long serverId, String messageId, OffsetDateTime collectedAt) {
        MetricsIngestionEntity ingestion = new MetricsIngestionEntity();
        ingestion.setServerId(serverId);
        ingestion.setMessageId(messageId);
        ingestion.setCollectedAt(collectedAt.atZoneSameInstant(APPLICATION_ZONE).toLocalDateTime());
        try {
            return metricsMapper.insertIngestion(ingestion) != 1;
        } catch (DuplicateKeyException exception) {
            return true;
        }
    }

    /**
     * 校验指标上报载荷的全部字段合法性，非法时抛出永久拒绝异常。
     *
     * @param authenticatedServerId 已认证服务器 ID
     * @param messageId 消息幂等 UUID
     * @param payload 指标上报载荷
     */
    private void validatePayload(Long authenticatedServerId, String messageId, MetricsReportPayload payload) {
        if (payload == null || authenticatedServerId == null || !isUuid(messageId)
                || !authenticatedServerId.equals(payload.getServerId())
                || payload.getCollectedAt() == null
                // 协议（websocket-protocol.md）与消息契约（message-contracts-v1.md）要求
                // collected_at 为 UTC ISO-8601；非 UTC offset 按永久非法载荷拒绝。
                || !ZoneOffset.UTC.equals(payload.getCollectedAt().getOffset())
                || payload.getCollectedAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5))) {
            throw new MetricsRejectedException(MetricsRejectionReason.INVALID_METRICS_PAYLOAD);
        }
        validatePercent(payload.getCpuPercent());
        validatePercent(payload.getMemoryPercent());
        validatePercent(payload.getDiskPercent());
        validateNonNegative(payload.getMemoryUsed());
        validateNonNegative(payload.getMemoryTotal());
        validateNonNegative(payload.getDiskUsed());
        validateNonNegative(payload.getDiskTotal());
        validateNonNegative(payload.getNetRx());
        validateNonNegative(payload.getNetTx());
        validateNonNegative(payload.getLoadAvg());
        validateProcessTop(payload.getProcessCpuTop());
        validateProcessTop(payload.getProcessMemTop());
        if (payload.getMemoryUsed() != null && payload.getMemoryTotal() != null
                && payload.getMemoryUsed() > payload.getMemoryTotal()
                || payload.getDiskUsed() != null && payload.getDiskTotal() != null
                && payload.getDiskUsed() > payload.getDiskTotal()) {
            throw new MetricsRejectedException(MetricsRejectionReason.INVALID_METRICS_PAYLOAD);
        }
    }

    /**
     * 校验可选 Top 进程列表的条数与元素合法性。
     *
     * @param samples 进程条目列表；旧版 Agent 整体省略时为 null
     */
    private void validateProcessTop(List<ProcessSamplePayload> samples) {
        if (samples == null) {
            return;
        }
        if (samples.size() > MAX_PROCESS_ENTRIES) {
            throw new MetricsRejectedException(MetricsRejectionReason.INVALID_METRICS_PAYLOAD);
        }
        for (ProcessSamplePayload sample : samples) {
            if (sample == null || sample.pid() == null || sample.pid() < 1
                    || sample.name() == null || sample.name().isBlank()
                    || sample.name().length() > MAX_PROCESS_NAME_LENGTH
                    || isOutsidePercent(sample.cpuPercent()) || isOutsidePercent(sample.memPercent())) {
                throw new MetricsRejectedException(MetricsRejectionReason.INVALID_METRICS_PAYLOAD);
            }
        }
    }

    /** 判断占比数值是否落在 0-100 之外；进程协议字段不允许缺省。 */
    private boolean isOutsidePercent(BigDecimal value) {
        return value == null || value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0;
    }

    /**
     * 将上报载荷中的可选进程排行转换为内存快照视图；两个排行均缺省时返回 null。
     *
     * @param payload 指标上报载荷
     * @return 进程快照；旧版 Agent 载荷返回 null
     */
    private ProcessSnapshotVo toProcessSnapshot(MetricsReportPayload payload) {
        if (payload.getProcessCpuTop() == null && payload.getProcessMemTop() == null) {
            return null;
        }
        List<ProcessSampleVo> cpuTop = toProcessSampleVos(payload.getProcessCpuTop());
        List<ProcessSampleVo> memTop = toProcessSampleVos(payload.getProcessMemTop());
        // 校验层已保证 collected_at 为 UTC offset，可直接透传。
        return new ProcessSnapshotVo(payload.getServerId(), payload.getCollectedAt(), cpuTop, memTop);
    }

    /**
     * 批量转换进程条目视图；空列表保持空列表，缺省（null）转为空列表以稳定快照形状。
     *
     * @param samples 上报的进程条目
     * @return 进程条目视图列表
     */
    private List<ProcessSampleVo> toProcessSampleVos(List<ProcessSamplePayload> samples) {
        if (samples == null) {
            return List.of();
        }
        return samples.stream()
                .map(sample -> new ProcessSampleVo(sample.pid(), sample.name(),
                        sample.cpuPercent(), sample.memPercent()))
                .toList();
    }

    /** UUID 是 Agent 至少一次投递的幂等键，格式无效时不能安全执行去重。 */
    private boolean isUuid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * 校验百分比值在 0-100 范围内。
     *
     * @param value 百分比值，允许 null
     */
    private void validatePercent(BigDecimal value) {
        if (value != null && (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new MetricsRejectedException(MetricsRejectionReason.INVALID_METRICS_PAYLOAD);
        }
    }

    /**
     * 校验数值为非负数。
     *
     * @param value 数值，允许 null
     */
    private void validateNonNegative(Number value) {
        if (value != null && value.doubleValue() < 0) {
            throw new MetricsRejectedException(MetricsRejectionReason.INVALID_METRICS_PAYLOAD);
        }
    }

    /**
     * 将上报载荷转换为指标实体。
     *
     * @param payload 指标上报载荷
     * @return 指标实体
     */
    private MetricsEntity toEntity(MetricsReportPayload payload) {
        MetricsEntity entity = new MetricsEntity();
        entity.setServerId(payload.getServerId());
        entity.setCpuPercent(payload.getCpuPercent());
        entity.setMemoryPercent(payload.getMemoryPercent());
        entity.setMemoryUsed(payload.getMemoryUsed());
        entity.setMemoryTotal(payload.getMemoryTotal());
        entity.setDiskPercent(payload.getDiskPercent());
        entity.setDiskUsed(payload.getDiskUsed());
        entity.setDiskTotal(payload.getDiskTotal());
        entity.setNetRx(payload.getNetRx());
        entity.setNetTx(payload.getNetTx());
        entity.setTemperature(payload.getTemperature());
        entity.setLoadAvg(payload.getLoadAvg());
        entity.setCollectedAt(payload.getCollectedAt().atZoneSameInstant(APPLICATION_ZONE).toLocalDateTime());
        return entity;
    }

    /**
     * 将指标实体转换为最新指标视图对象。
     *
     * @param entity 指标实体
     * @return 最新指标视图对象
     */
    private MetricsLatestVo toLatestVo(MetricsEntity entity) {
        MetricsLatestVo result = new MetricsLatestVo();
        result.setServerId(entity.getServerId());
        result.setCpuPercent(entity.getCpuPercent());
        result.setMemoryPercent(entity.getMemoryPercent());
        result.setMemoryUsed(entity.getMemoryUsed());
        result.setMemoryTotal(entity.getMemoryTotal());
        result.setDiskPercent(entity.getDiskPercent());
        result.setDiskUsed(entity.getDiskUsed());
        result.setDiskTotal(entity.getDiskTotal());
        result.setNetRx(entity.getNetRx());
        result.setNetTx(entity.getNetTx());
        result.setTemperature(entity.getTemperature());
        result.setLoadAvg(entity.getLoadAvg());
        result.setCollectedAt(entity.getCollectedAt().atOffset(ZoneOffset.UTC));
        return result;
    }

    /**
     * 将指标实体转换为历史指标视图对象。
     *
     * @param entity 指标实体
     * @return 历史指标视图对象
     */
    private MetricsHistoryVo toHistoryVo(MetricsEntity entity) {
        MetricsHistoryVo result = new MetricsHistoryVo();
        MetricsLatestVo latest = toLatestVo(entity);
        result.setServerId(latest.getServerId());
        result.setCpuPercent(latest.getCpuPercent());
        result.setMemoryPercent(latest.getMemoryPercent());
        result.setMemoryUsed(latest.getMemoryUsed());
        result.setMemoryTotal(latest.getMemoryTotal());
        result.setDiskPercent(latest.getDiskPercent());
        result.setDiskUsed(latest.getDiskUsed());
        result.setDiskTotal(latest.getDiskTotal());
        result.setNetRx(latest.getNetRx());
        result.setNetTx(latest.getNetTx());
        result.setTemperature(latest.getTemperature());
        result.setLoadAvg(latest.getLoadAvg());
        result.setCollectedAt(latest.getCollectedAt());
        return result;
    }
}
