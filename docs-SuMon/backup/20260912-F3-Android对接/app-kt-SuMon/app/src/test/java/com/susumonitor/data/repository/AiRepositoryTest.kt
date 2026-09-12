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
    }
}
