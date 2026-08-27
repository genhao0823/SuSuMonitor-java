package com.susumonitor.data.repository

import com.susumonitor.api.MetricsApi
import com.susumonitor.data.ApiException
import com.susumonitor.data.model.Metrics
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 指标仓库：最新值 / 历史分页查询。
 */
@Singleton
class MetricsRepository @Inject constructor(
    private val metricsApi: MetricsApi,
) {

    /** 最新固定宽表指标。 */
    suspend fun latest(serverId: Long): Metrics {
        val response = metricsApi.getLatest(serverId)
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** 历史指标（时间范围 UTC ISO-8601）。 */
    suspend fun history(serverId: Long, startTime: String, endTime: String): List<Metrics> {
        val response = metricsApi.getHistory(serverId, startTime, endTime)
        return response.data?.items ?: throw ApiException.Business(response.code, response.message)
    }
}
