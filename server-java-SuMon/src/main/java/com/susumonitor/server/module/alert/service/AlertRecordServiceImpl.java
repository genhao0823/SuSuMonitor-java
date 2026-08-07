package com.susumonitor.server.module.alert.service;

import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.common.vo.PageResult;
import com.susumonitor.server.module.alert.entity.AlertNotificationEntity;
import com.susumonitor.server.module.alert.entity.AlertRecordEntity;
import com.susumonitor.server.module.alert.mapper.AlertNotificationMapper;
import com.susumonitor.server.module.alert.mapper.AlertRecordMapper;
import com.susumonitor.server.module.alert.vo.AlertNotificationVo;
import com.susumonitor.server.module.alert.vo.AlertRecordVo;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 告警记录查询和已读标记服务。
 *
 * <p>分页查询支持按服务器 ID 和状态筛选。
 * 标记已读从 unread 转为 read，已恢复的记录不可标记已读。</p>
 */
@Service
@RequiredArgsConstructor
public class AlertRecordServiceImpl implements AlertRecordService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AlertRecordMapper recordMapper;
    private final AlertNotificationMapper notificationMapper;
    private final Clock clock;

    /** 分页查询告警记录。 */
    @Transactional(readOnly = true)
    public PageResult<AlertRecordVo> listRecords(Long serverId, String status, Integer page, Integer pageSize) {
        if (page == null || page < 1 || pageSize == null || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        long offset = (long) (page - 1) * pageSize;
        List<AlertRecordEntity> entities = recordMapper.selectRecords(serverId, status, offset, pageSize);
        List<AlertRecordVo> items = entities.stream().map(this::toVo).toList();
        PageResult<AlertRecordVo> result = new PageResult<>();
        result.setItems(items);
        result.setTotal(recordMapper.countRecords(serverId, status));
        result.setPage(page);
        result.setPageSize(pageSize);
        return result;
    }

    /** 标记告警记录为已读，仅 unread 状态可标记。 */
    @Transactional
    public void markAsRead(Long recordId, Long userId) {
        AlertRecordEntity entity = recordMapper.selectRecordById(recordId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        recordMapper.updateStatusToRead(recordId, userId, LocalDateTime.now(clock));
    }

    /** 查询某告警记录的通知投递历史。 */
    @Transactional(readOnly = true)
    public List<AlertNotificationVo> listNotifications(Long recordId) {
        AlertRecordEntity record = recordMapper.selectRecordById(recordId);
        if (record == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return notificationMapper.selectByRecordId(recordId).stream()
                .map(this::toNotificationVo)
                .toList();
    }

    /** 将 Entity 转换为 VO，时间字段转为 UTC OffsetDateTime。 */
    private AlertRecordVo toVo(AlertRecordEntity entity) {
        AlertRecordVo vo = new AlertRecordVo();
        vo.setId(entity.getId());
        vo.setRuleId(entity.getRuleId());
        vo.setServerId(entity.getServerId());
        vo.setMetric(entity.getMetric());
        vo.setCurrentValue(entity.getCurrentValue());
        vo.setThresholdValue(entity.getThresholdValue());
        vo.setLevel(entity.getLevel());
        vo.setStatus(entity.getStatus());
        vo.setMessage(entity.getMessage());
        vo.setReadBy(entity.getReadBy());
        vo.setReadAt(AlertRecordVo.toOffset(entity.getReadAt()));
        vo.setTriggeredAt(AlertRecordVo.toOffset(entity.getTriggeredAt()));
        vo.setNotifiedAt(AlertRecordVo.toOffset(entity.getNotifiedAt()));
        vo.setNotifyChannels(entity.getNotifyChannels());
        vo.setCreatedAt(AlertRecordVo.toOffset(entity.getCreatedAt()));
        return vo;
    }

    /** 将通知 Entity 转换为 VO。 */
    private AlertNotificationVo toNotificationVo(AlertNotificationEntity entity) {
        AlertNotificationVo vo = new AlertNotificationVo();
        vo.setId(entity.getId());
        vo.setAlertRecordId(entity.getAlertRecordId());
        vo.setChannel(entity.getChannel());
        vo.setStatus(entity.getStatus());
        vo.setAttempts(entity.getAttempts());
        vo.setNextAttemptAt(AlertNotificationVo.toOffset(entity.getNextAttemptAt()));
        vo.setLastError(entity.getLastError());
        vo.setCreatedAt(AlertNotificationVo.toOffset(entity.getCreatedAt()));
        vo.setUpdatedAt(AlertNotificationVo.toOffset(entity.getUpdatedAt()));
        return vo;
    }
}
