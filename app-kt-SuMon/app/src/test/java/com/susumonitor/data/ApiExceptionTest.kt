package com.susumonitor.data

import io.mockk.every
import io.mockk.mockk
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ApiException 映射测试：业务错误体解析 + 网络/解析失败分类。
 */
class ApiExceptionTest {

    private val jsonMediaType = "application/json".toMediaType()

    @Test
    fun `http error with business body extracts code and message`() {
        val errorBody = """{"code":40900,"message":"resource conflict","data":null}"""
            .toResponseBody(jsonMediaType)
        val response = Response.error<Any>(409, errorBody)
        val httpException = HttpException(response)

        val exception = ApiException.from(httpException)

        assertTrue(exception is ApiException.Business)
        assertEquals(40900, (exception as ApiException.Business).code)
        assertEquals("resource conflict", exception.message)
    }

    @Test
    fun `http error without business body falls back to status code`() {
        val errorBody = "plain text".toResponseBody("text/plain".toMediaType())
        val response = Response.error<Any>(500, errorBody)
        val exception = ApiException.from(HttpException(response))

        assertTrue(exception is ApiException.Business)
        assertEquals(0, (exception as ApiException.Business).code)
        assertEquals("HTTP 500", exception.message)
    }

    @Test
    fun `network error is classified`() {
        val exception = ApiException.from(java.io.IOException("timeout"))
        assertTrue(exception is ApiException.Network)
        assertEquals("timeout", exception.message)
    }

    @Test
    fun `network error without message uses default hint`() {
        val exception = ApiException.from(java.io.IOException())
        assertTrue(exception is ApiException.Network)
        assertTrue(exception.message.orEmpty().contains("网络"))
    }

    @Test
    fun `parse error is classified`() {
        val exception = ApiException.from(kotlinx.serialization.SerializationException("bad json"))
        assertTrue(exception is ApiException.Parse)
    }
}
