package com.susumonitor.server.module.alert.service;

import com.susumonitor.server.common.vo.PageResult;
import com.susumonitor.server.module.alert.vo.AlertNotificationVo;
import com.susumonitor.server.module.alert.vo.AlertRecordVo;
import java.util.List;

/**
 * 定义告警记录查询和已读状态维护的业务契约。
 */
public interface AlertRecordService {

    /** 分页查询告警记录。 */
    PageResult<AlertRecordVo> listRecords(Long serverId, String status, Integer page, Integer pageSize);

    /** 将未读告警记录标记为已读。 */
    void markAsRead(Long recordId, Long userId);

    /** 查询某告警记录的通知投递历史（按渠道/ID 升序），记录不存在时抛 RESOURCE_NOT_FOUND。 */
    List<AlertNotificationVo> listNotifications(Long recordId);
}
