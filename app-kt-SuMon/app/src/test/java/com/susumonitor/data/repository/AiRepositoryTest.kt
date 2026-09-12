package com.susumonitor.data.repository

import com.susumonitor.api.AiApi
import com.susumonitor.data.model.AiDiagnosisRequest
import com.susumonitor.data.model.ApiResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AiRepository 单元测试：404 → AI 未启用、42906 → 限流的错误语义映射。
 */
class AiRepositoryTest {

    private val aiApi = mockk<AiApi>()
    private val repository = AiRepository(aiApi, mockk())

    @Test
    fun `diagnose returns data on success`() = runTest {
        coEvery { aiApi.diagnose(any()) } returns ApiResponse(0, "success", diagnosisStub)

        val result = repository.diagnose(AiDiagnosisRequest(1, "CPU 为什么高？", 60))

        assertEquals("warning", result.severity)
        coVerify { aiApi.diagnose(any()) }
    }

    @Test
    fun `diagnose maps 404 to AiNotEnabledException`() = runTest {
        coEvery { aiApi.diagnose(any()) } returns ApiResponse(40400, "resource not found", null)

        val error = runCatching {
            repository.diagnose(AiDiagnosisRequest(1, "q", 30))
        }.exceptionOrNull()

        assertTrue(error is AiRepository.AiNotEnabledException)
    }

    @Test
    fun `diagnose maps 42906 to AiRateLimitedException`() = runTest {
        coEvery { aiApi.diagnose(any()) } returns ApiResponse(42906, "AI rate limit reached", null)

        val error = runCatching {
            repository.diagnose(AiDiagnosisRequest(1, "q", 30))
        }.exceptionOrNull()

        assertTrue(error is AiRepository.AiRateLimitedException)
    }

    private companion object {
        val diagnosisStub = com.susumonitor.data.model.AiDiagnosis(
            summary = "CPU 饱和",
            severity = "warning",
            modelUsed = true,
        )

        val healthReportStub = com.susumonitor.data.model.AiHealthReport(
            id = 9L,
            reportDate = "2026-09-11",
            status = "succeeded",
        )
    }

    @Test
    fun `listHealthReports returns page on success`() = runTest {
        coEvery { aiApi.listHealthReports(page = 1, pageSize = 20) } returns ApiResponse(
            0,
            "success",
            com.susumonitor.data.model.PageResult(items = listOf(healthReportStub), total = 1, page = 1, page_size = 20),
        )

        val result = repository.listHealthReports(page = 1, pageSize = 20)

        assertEquals(1L, result.total)
        assertEquals(9L, result.items.first().id)
    }

    @Test
    fun `generateHealthReport returns degraded report on success`() = runTest {
        coEvery { aiApi.generateHealthReport(any()) } returns ApiResponse(
            0,
            "success",
            healthReportStub.copy(id = 10L, status = "degraded", errorCode = 42906, summary = null),
        )

        val result = repository.generateHealthReport(reportDate = null)

        assertEquals(10L, result.id)
        assertEquals("degraded", result.status)
        assertEquals(42906, result.errorCode)
    }

    @Test
    fun `listHealthReports maps 404 to AiNotEnabledException`() = runTest {
        coEvery { aiApi.listHealthReports(any(), any()) } returns
            ApiResponse(40400, "resource not found", null)

        val error = runCatching { repository.listHealthReports(1, 20) }.exceptionOrNull()

        assertTrue(error is AiRepository.AiNotEnabledException)
    }

    @Test
    fun `generateHealthReport maps 42906 to AiRateLimitedException`() = runTest {
        coEvery { aiApi.generateHealthReport(any()) } returns
            ApiResponse(42906, "AI rate limit reached", null)

        val error = runCatching { repository.generateHealthReport(null) }.exceptionOrNull()

        assertTrue(error is AiRepository.AiRateLimitedException)
    }

    @Test
    fun `healthReport maps 404 to AiNotEnabledException`() = runTest {
        coEvery { aiApi.getHealthReport(404L) } returns ApiResponse(40400, "resource not found", null)

        val error = runCatching { repository.healthReport(404L) }.exceptionOrNull()

        assertTrue(error is AiRepository.AiNotEnabledException)
    }
}
