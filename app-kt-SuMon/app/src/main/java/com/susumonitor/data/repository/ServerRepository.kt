package com.susumonitor.data.repository

import com.susumonitor.api.ServerApi
import com.susumonitor.data.ApiException
import com.susumonitor.data.model.AgentTokenVo
import com.susumonitor.data.model.ConfirmSshHostKeyRequest
import com.susumonitor.data.model.CreateServerRequest
import com.susumonitor.data.model.PageResult
import com.susumonitor.data.model.Server
import com.susumonitor.data.model.ServerQuery
import com.susumonitor.data.model.ServerStatus
import com.susumonitor.data.model.SshHostKeyVo
import com.susumonitor.data.model.SshTestResult
import com.susumonitor.data.model.UpdateServerRequest
import com.susumonitor.util.ErrorCodes
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务器仓库：列表/详情/状态 + 管理（CRUD/SSH 测试/主机指纹/Agent Token）。
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
            sortBy = query.sortBy,
            sortOrder = query.sortOrder,
        )
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** 单台服务器详情。 */
    suspend fun get(id: Long): Server {
        val response = serverApi.getServer(id)
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** 创建服务器（admin）。 */
    suspend fun create(request: CreateServerRequest): Server {
        val response = serverApi.createServer(request)
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** 更新服务器（admin）。 */
    suspend fun update(id: Long, request: UpdateServerRequest): Server {
        val response = serverApi.updateServer(id, request)
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** 删除服务器（admin，软删除）。 */
    suspend fun delete(id: Long) {
        val response = serverApi.deleteServer(id)
        if (response.code != ErrorCodes.SUCCESS) {
            throw ApiException.Business(response.code, response.message)
        }
    }

    /** 服务器状态快照。 */
    suspend fun status(id: Long): ServerStatus {
        val response = serverApi.getServerStatus(id)
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** SSH 连接测试（admin）。 */
    suspend fun testSsh(id: Long): SshTestResult {
        val response = serverApi.testSsh(id)
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** 确认/轮换 SSH 主机公钥指纹（admin）。 */
    suspend fun confirmHostKey(id: Long, expectedFingerprint: String, replace: Boolean): SshHostKeyVo {
        val response = serverApi.confirmSshHostKey(
            id,
            ConfirmSshHostKeyRequest(expectedFingerprint = expectedFingerprint, replace = replace),
        )
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** 生成 Agent Token（admin；明文仅此响应）。 */
    suspend fun registerAgentToken(id: Long): AgentTokenVo {
        val response = serverApi.registerAgentToken(id)
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** 轮换 Agent Token（admin；明文仅此响应）。 */
    suspend fun rotateAgentToken(id: Long): AgentTokenVo {
        val response = serverApi.rotateAgentToken(id)
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** 吊销 Agent Token（admin）。 */
    suspend fun revokeAgentToken(id: Long) {
        val response = serverApi.revokeAgentToken(id)
        if (response.code != ErrorCodes.SUCCESS) {
            throw ApiException.Business(response.code, response.message)
        }
    }
}
