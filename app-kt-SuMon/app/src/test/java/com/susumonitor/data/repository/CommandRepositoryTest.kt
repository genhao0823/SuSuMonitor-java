package com.susumonitor.data.repository

import com.susumonitor.api.CommandApi
import com.susumonitor.data.model.ApiResponse
import com.susumonitor.data.model.AutoApprovalPolicy
import com.susumonitor.data.model.CommandTemplate
import com.susumonitor.data.model.TemplateParamSpec
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CommandRepository 单元测试：模板参数正则预校验 + 404/42907 错误语义映射。
 */
class CommandRepositoryTest {

    private val commandApi = mockk<CommandApi>()
    private val repository = CommandRepository(commandApi)

    private val template = CommandTemplate(
        id = "disk_top_usage",
        argv = listOf("du", "-h", "{path}"),
        params = listOf(TemplateParamSpec("path", "^[A-Za-z0-9/_.-]{1,128}$")),
    )

    @Test
    fun `validateParams accepts matching value`() {
        assertNull(repository.validateParams(template, mapOf("path" to "/var/log")))
    }

    @Test
    fun `validateParams rejects pattern mismatch and blank`() {
        // 分号注入被正则拒绝
        assertEquals("path", repository.validateParams(template, mapOf("path" to "/tmp; rm -rf /")))
        assertEquals("path", repository.validateParams(template, mapOf("path" to "")))
        assertEquals("path", repository.validateParams(template, emptyMap()))
    }

    @Test
    fun `validateParams passes for template without params`() {
        val noParams = CommandTemplate(id = "uptime", argv = listOf("uptime"), params = emptyList())
        assertNull(repository.validateParams(noParams, emptyMap()))
    }

    @Test
    fun `templates returns list on success`() = runTest {
        coEvery { commandApi.templates() } returns ApiResponse(0, "success", listOf(template))

        val result = repository.templates()

        assertEquals(1, result.size)
        assertEquals("disk_top_usage", result.first().id)
    }

    @Test
    fun `templates maps 404 to not enabled`() = runTest {
        coEvery { commandApi.templates() } returns ApiResponse(40400, "resource not found", null)

        val error = runCatching { repository.templates() }.exceptionOrNull()

        assertTrue(error is CommandRepository.CommandDomainNotEnabledException)
    }

    @Test
    fun `createManual maps 42907 to rate limited`() = runTest {
        coEvery { commandApi.createManual(any()) } returns ApiResponse(42907, "command rate limit reached", null)

        val error = runCatching {
            repository.createManual(1, "uptime", emptyMap())
        }.exceptionOrNull()

        assertTrue(error is CommandRepository.CommandRateLimitedException)
    }

    @Test
    fun `autoApprovalPolicy returns snapshot on success`() = runTest {
        coEvery { commandApi.getAutoApprovalPolicy() } returns
            ApiResponse(0, "success", AutoApprovalPolicy(enabled = false, maxRiskLevel = "medium"))

        val policy = repository.autoApprovalPolicy()

        assertEquals(false, policy.enabled)
        assertEquals("medium", policy.maxRiskLevel)
    }

    @Test
    fun `updateAutoApprovalPolicy sends request and returns snapshot`() = runTest {
        coEvery { commandApi.updateAutoApprovalPolicy(any()) } returns
            ApiResponse(0, "success", AutoApprovalPolicy(enabled = true, maxRiskLevel = "low"))

        val policy = repository.updateAutoApprovalPolicy(enabled = true, maxRiskLevel = "low")

        assertEquals(true, policy.enabled)
        assertEquals("low", policy.maxRiskLevel)
        coEvery { commandApi.updateAutoApprovalPolicy(any()) } answers { firstArg() }
    }
}
