package com.susumonitor.data.repository

import com.susumonitor.api.ServerApi
import com.susumonitor.data.ApiException
import com.susumonitor.data.model.PageResult
import com.susumonitor.data.model.Server
import com.susumonitor.data.model.ServerQuery
import com.susumonitor.data.model.ServerStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务器仓库：列表 / 详情 / 状态快照。
 */
@Singleton
class ServerRepository @Inject constructor(
    private val serverApi: ServerApi,
) {

    /** 分页查询服务器列表。 */
    suspend fun list(query: ServerQuery): PageResult<Server> {
        val response = serverApi.listServers(
            page = query.page,
            pageSize = query.pageSize,
            keyword = query.keyword,
        )
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** 单台服务器详情。 */
    suspend fun get(id: Long): Server {
        val response = serverApi.getServer(id)
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** 服务器状态快照。 */
    suspend fun status(id: Long): ServerStatus {
        val response = serverApi.getServerStatus(id)
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }
}
