package com.susumonitor.data

import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import retrofit2.Response
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
         * 非 2xx 响应尽量解析响应体中的 `{code,message}` 业务字段，
         * 解析失败则退回 HTTP 状态码文案。
         */
        fun from(t: Throwable): ApiException = when (t) {
            is HttpException -> fromHttp(t.response())
            is IOException -> Network(t.message ?: "网络连接失败，请检查网络后重试")
            is SerializationException -> Parse(t.message ?: "响应解析失败")
            else -> Business(0, t.message ?: "未知错误")
        }

        private fun fromHttp(response: Response<*>?): ApiException {
            if (response == null) {
                return Business(0, "HTTP 请求失败")
            }
            // 尝试解析错误体 {code,message}
            response.errorBody()?.string()?.let { raw ->
                runCatching {
                    val parsed = AppJson.decodeFromString<com.susumonitor.data.model.ApiResponse<Unit>>(raw)
                    if (parsed.code != 0) {
                        return Business(parsed.code, parsed.message)
                    }
                }
            }
            return Business(0, "HTTP ${response.code()}")
        }
    }
}
