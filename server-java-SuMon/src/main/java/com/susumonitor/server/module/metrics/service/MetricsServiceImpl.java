package com.susumonitor.server.module.metrics.service;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.common.vo.PageResult;
import com.susumonitor.server.module.metrics.dto.MetricsReportPayload;
import com.susumonitor.server.module.metrics.entity.MetricsEntity;
import com.susumonitor.server.module.metrics.entity.MetricsIngestionEntity;
import com.susumonitor.server.module.metrics.mapper.MetricsMapper;
import com.susumonitor.server.module.metrics.outbox.OutboxService;
import com.susumonitor.server.module.metrics.vo.MetricsHistoryVo;
import com.susumonitor.server.module.metrics.vo.MetricsLatestVo;
import com.susumonitor.server.module.server.service.ServerService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 负责固定宽表指标校验、写入、最新值和历史分页查询。 */
@Service
public class MetricsServiceImpl implements MetricsService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_HISTORY_DAYS = 7;
    private static final ZoneId APPLICATION_ZONE = ZoneOffset.UTC;
    private final MetricsMapper metricsMapper;
    private final ServerService serverService;
    private final OutboxService outboxService;
    private final ApplicationEventPublisher eventPublisher;

    /** 注入指标数据访问组件、服务器契约与 Outbox 登记服务（servers 表访问统一走 ServerService）。 */
    public MetricsServiceImpl(MetricsMapper metricsMapper, ServerService serverService,
            OutboxService outboxService, ApplicationEventPublisher eventPublisher) {
        this.metricsMapper = metricsMapper;
        this.serverService = serverService;
        this.outboxService = outboxService;
        this.eventPublisher = eventPublisher;
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
     */
    @Transactional
    public void report(Long authenticatedServerId, String messageId, MetricsReportPayload payload) {
        validatePayload(authenticatedServerId, messageId, payload);
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
        outboxService.enqueue(entity, messageId);
        eventPublisher.publishEvent(new MetricsReportedEvent(toLatestVo(entity)));
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
        long offset = (long) (page - 1) * pageSize;
        List<MetricsHistoryVo> items = metricsMapper.selectHistory(serverId, start, end, offset, pageSize)
                .stream().map(this::toHistoryVo).toList();
        PageResult<MetricsHistoryVo> result = new PageResult<>();
        result.setItems(items);
        result.setTotal(metricsMapper.countHistory(serverId, start, end));
        result.setPage(page);
        result.setPageSize(pageSize);
        return result;
    }

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

    private void validatePayload(Long authenticatedServerId, String messageId, MetricsReportPayload payload) {
        if (payload == null || authenticatedServerId == null || !isUuid(messageId)
                || !authenticatedServerId.equals(payload.getServerId())
                || payload.getCollectedAt() == null
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
        if (payload.getMemoryUsed() != null && payload.getMemoryTotal() != null
                && payload.getMemoryUsed() > payload.getMemoryTotal()
                || payload.getDiskUsed() != null && payload.getDiskTotal() != null
                && payload.getDiskUsed() > payload.getDiskTotal()) {
            throw new MetricsRejectedException(MetricsRejectionReason.INVALID_METRICS_PAYLOAD);
        }
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

    private void validatePercent(BigDecimal value) {
        if (value != null && (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new MetricsRejectedException(MetricsRejectionReason.INVALID_METRICS_PAYLOAD);
        }
    }

    private void validateNonNegative(Number value) {
        if (value != null && value.doubleValue() < 0) {
            throw new MetricsRejectedException(MetricsRejectionReason.INVALID_METRICS_PAYLOAD);
        }
    }

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
