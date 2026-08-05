package com.susumonitor.data.model

import com.susumonitor.data.AppJson
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DTO 反序列化测试：JSON 样本取自 OpenAPI 契约示例。
 */
class AuthModelsTest {

    @Test
    fun `login response decodes from openapi example`() {
        val json = """
            {
              "code": 0,
              "message": "success",
              "data": {
                "token": "REPLACE_WITH_JWT",
                "tokenType": "Bearer",
                "expiresIn": 259200,
                "user": {
                  "id": 1,
                  "username": "admin",
                  "role": "admin",
                  "reviewStatus": "approved",
                  "reviewedAt": "2026-07-20T00:00:00Z",
                  "createdAt": "2026-07-20T00:00:00Z"
                }
              }
            }
        """.trimIndent()

        val response: ApiResponse<LoginVo> = AppJson.decodeFromString(json)
        assertEquals(0, response.code)
        assertEquals("success", response.message)
        val vo = response.data
        assertNotNull(vo)
        assertEquals("REPLACE_WITH_JWT", vo!!.token)
        assertEquals("Bearer", vo.tokenType)
        assertEquals(259200L, vo.expiresIn)
        assertEquals("admin", vo.user.role)
        assertEquals("approved", vo.user.reviewStatus)
    }

    @Test
    fun `error response decodes with null data`() {
        val json = """
            {"code": 40100, "message": "unauthorized", "data": null}
        """.trimIndent()

        val response: ApiResponse<Unit> = AppJson.decodeFromString(json)
        assertEquals(40100, response.code)
        assertNull(response.data)
    }
}
