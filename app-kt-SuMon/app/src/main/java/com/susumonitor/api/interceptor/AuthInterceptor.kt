package com.susumonitor.api.interceptor

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
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
 * Token 通过挂起的 [tokenProvider] 获取；拦截器内同步读取以兼容 OkHttp 同步模型。
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenProvider: TokenProvider,
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
        return chain.proceed(request)
    }
}

/** 提供当前 JWT（由 SessionStore 实现，避免拦截器依赖具体存储）。 */
fun interface TokenProvider {
    suspend fun currentToken(): String?
}
