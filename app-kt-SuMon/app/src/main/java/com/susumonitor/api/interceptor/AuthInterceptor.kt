package com.susumonitor.api.interceptor

import com.susumonitor.data.SessionStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 为每个请求注入认证与链路头：
 * - `Authorization: Bearer <jwt>`（会话存在时）
 * - `X-Request-ID`：可选请求追踪 ID，服务端缺省生成
 * - `X-Correlation-ID`：客户端生成的关联 ID（同请求多次重试保持一致）
 *
 * 同时处理 401 响应：仅在「本地 token 仍是发起请求时的值」时清空本地会话
 * （防旧请求的 401 误清用户重新登录后的新会话）；WebSocket 升级请求的 401
 * （ticket 过期）只触发重连，不视为登录态失效。
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenProvider: TokenProvider,
    private val sessionStore: SessionStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { tokenProvider.currentToken() }
        val request = chain.request().newBuilder()
            .header("X-Correlation-ID", UUID.randomUUID().toString())
            .apply {
                if (!token.isNullOrEmpty()) {
                    header("Authorization", "Bearer $token")
                }
            }
            .build()
        val response = chain.proceed(request)
        // 401 = 会话失效：仅当请求携带了 token 且本地 token 未变时才清会话（幂等）。
        if (response.code == 401 && token != null && !isWebSocketUpgrade(request)) {
            runBlocking {
                if (sessionStore.currentToken() == token) {
                    sessionStore.clear()
                }
            }
        }
        return response
    }

    /** Monitor/Agent WebSocket 升级请求：401 由 ticket 过期或服务端关闭引起，不应触发登出。 */
    private fun isWebSocketUpgrade(request: Request): Boolean =
        request.url.encodedPath.startsWith("/ws/")
}

/** 提供当前 JWT（由 SessionStore 实现，避免拦截器依赖具体存储）。 */
fun interface TokenProvider {
    suspend fun currentToken(): String?
}
