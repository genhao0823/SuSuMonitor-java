package com.susumonitor.data

import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException

/**
 * App 统一异常模型：将网络层失败映射为可提示的用户错误。
 */
sealed class ApiException(message: String) : Exception(message) {

    /** 后端返回的业务错误（HTTP 2xx 但 code!=0，或明确的 4xx/5xx 业务码）。 */
    data class Business(val code: Int, override val message: String) : ApiException(message)

    /** 网络不可达 / 超时 / DNS 等 IO 类失败。 */
    data class Network(override val message: String) : ApiException(message)

    /** 响应体解析失败（契约漂移）。 */
    data class Parse(override val message: String) : ApiException(message)

    companion object {
        /**
         * 将 Retrofit 抛出的异常映射为 [ApiException]。
         * 优先级：HTTP 业务体 code → HTTP 状态码 → IO → 解析。
         */
        fun from(t: Throwable): ApiException = when (t) {
            is HttpException -> {
                // 非 2xx：尝试读取 body 中的 {code,message}
                val code = t.code()
                Business(code, "HTTP $code")
            }
            is IOException -> Network(t.message ?: "网络连接失败，请检查网络后重试")
            is SerializationException -> Parse(t.message ?: "响应解析失败")
            else -> Business(0, t.message ?: "未知错误")
        }
    }
}
