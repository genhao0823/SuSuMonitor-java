package com.susumonitor.data.repository

import com.susumonitor.api.SystemApi
import com.susumonitor.data.ApiException
import com.susumonitor.data.model.HealthStatus
import com.susumonitor.data.model.MonitorTicket
import com.susumonitor.data.model.ReadyStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 系统仓库：健康检查 + 就绪检查 + Monitor Ticket 获取。
 */
@Singleton
class SystemRepository @Inject constructor(
    private val systemApi: SystemApi,
) {

    /** 后端存活状态。 */
    suspend fun health(): HealthStatus {
        val response = systemApi.health()
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** 后端 + MySQL + RabbitMQ 就绪状态。 */
    suspend fun ready(): ReadyStatus {
        val response = systemApi.ready()
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** 获取 30 秒一次性 Monitor Ticket（WS 连接握手用）。 */
    suspend fun monitorTicket(): MonitorTicket {
        val response = systemApi.monitorTicket()
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }
}
