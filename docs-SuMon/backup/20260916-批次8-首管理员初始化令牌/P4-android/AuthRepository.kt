package com.susumonitor.data.repository

import com.susumonitor.api.AuthApi
import com.susumonitor.data.ApiException
import com.susumonitor.data.SessionStore
import com.susumonitor.data.model.CurrentUser
import com.susumonitor.data.model.LoginRequest
import com.susumonitor.data.model.LoginVo
import com.susumonitor.data.model.RegisterRequest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 认证仓库：负责登录/注册/登出及会话持久化。
 */
@Singleton
class AuthRepository @Inject constructor(
    private val authApi: AuthApi,
    private val sessionStore: SessionStore,
) {

    /** 登录成功保存会话，返回用户信息。 */
    suspend fun login(username: String, password: String): CurrentUser {
        val response = authApi.login(LoginRequest(username, password))
        val loginVo = response.data ?: throw ApiException.Business(response.code, response.message)
        sessionStore.saveSession(loginVo.token, loginVo.user)
        return loginVo.user
    }

    /** 注册（不自动登录）。 */
    suspend fun register(username: String, password: String): CurrentUser {
        val response = authApi.register(RegisterRequest(username, password))
        return response.data ?: throw ApiException.Business(response.code, response.message)
    }

    /** 获取当前用户最新状态（用于刷新会话角色/审核状态）。 */
    suspend fun refreshCurrentUser(): CurrentUser? {
        val response = authApi.me()
        return response.data
    }

    /** 登出：先清除本地会话（立即生效，不依赖网络），再尽力通知后端撤销 token。 */
    suspend fun logout() {
        sessionStore.clear()
        runCatching { authApi.logout() }
    }
}
