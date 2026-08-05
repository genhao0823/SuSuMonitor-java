package com.susumonitor.data.repository

import com.susumonitor.api.AlertApi
import com.susumonitor.data.ApiException
import com.susumonitor.data.model.AlertRecord
import com.susumonitor.data.model.AlertRecordQuery
import com.susumonitor.data.model.AlertRule
import com.susumonitor.data.model.PageResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 告警仓库：规则列表 / 记录分页 / 标记已读。
 */
@Singleton
class AlertRepository @Inject constructor(
    private val alertApi: AlertApi,
) {

    /** 告警规则列表（本版只读展示）。 */
    suspend fun listRules(): List<AlertRule> {
        val response = alertApi.listRules()
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** 告警记录分页。 */
    suspend fun listRecords(query: AlertRecordQuery): PageResult<AlertRecord> {
        val response = alertApi.listRecords(
            page = query.page,
            pageSize = query.pageSize,
            serverId = query.serverId,
            status = query.status,
        )
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** 标记记录已读。 */
    suspend fun markRead(id: Long) {
        val response = alertApi.markRead(id)
        if (response.code != com.susumonitor.util.ErrorCodes.SUCCESS) {
            throw ApiException.Business(response.code, response.message)
        }
    }
}
