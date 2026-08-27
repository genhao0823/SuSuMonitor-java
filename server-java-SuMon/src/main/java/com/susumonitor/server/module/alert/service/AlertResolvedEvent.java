package com.susumonitor.server.module.alert.service;

import com.susumonitor.server.module.alert.vo.AlertRecordVo;

/**
 * 告警恢复事件，在告警评估恢复事务提交后发布，供 AlertPushPublisher 推送。
 *
 * <p>使用 Spring ApplicationEvent 机制，配合
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 确保只在告警记录
 * 恢复状态与 resolved_at 写入事务提交后才推送 WebSocket。</p>
 *
 * <p>与 {@link AlertTriggeredEvent} 对称：record 的 status 为
 * {@code resolved}，resolvedAt 已落库。</p>
 *
 * @param serverId 恢复告警的服务器 ID
 * @param record   恢复后的告警记录 VO
 */
public record AlertResolvedEvent(Long serverId, AlertRecordVo record) {
}
