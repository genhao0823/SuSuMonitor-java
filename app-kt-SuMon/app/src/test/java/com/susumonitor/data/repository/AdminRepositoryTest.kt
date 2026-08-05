package com.susumonitor.data.repository

import com.susumonitor.api.AdminApi
import com.susumonitor.data.model.ApiResponse
import com.susumonitor.data.model.AdminUserVo
import com.susumonitor.data.model.BatchReviewResult
import com.susumonitor.data.model.PageResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AdminRepository 单元测试：列表/批量审核。
 */
class AdminRepositoryTest {

    private val adminApi = mockk<AdminApi>()
    private val repository = AdminRepository(adminApi)

    @Test
    fun `listUsers passes query params`() = runTest {
        coEvery {
            adminApi.listUsers(page = 1, pageSize = 20, status = "pending", keyword = "bob")
        } returns ApiResponse(
            0,
            "success",
            PageResult(
                items = listOf(
                    AdminUserVo(2L, "bob", "user", "pending", "2026-08-05T10:00:00Z"),
                ),
                total = 1,
                page = 1,
                page_size = 20,
            ),
        )

        val result = repository.listUsers(
            com.susumonitor.data.model.AdminUserQuery(
                status = "pending",
                keyword = "bob",
                page = 1,
                pageSize = 20,
            ),
        )

        assertEquals(1L, result.total)
        assertEquals("bob", result.items.first().username)
    }

    @Test
    fun `batchApprove maps result`() = runTest {
        coEvery { adminApi.batchApprove(any()) } returns ApiResponse(
            0,
            "success",
            BatchReviewResult(processed = 2, failed = 1, failedIds = listOf(5L)),
        )

        val result = repository.batchApprove(listOf(1L, 2L, 5L))

        assertEquals(2, result.processed)
        assertEquals(listOf(5L), result.failedIds)
    }

    @Test
    fun `batchReject maps result`() = runTest {
        coEvery { adminApi.batchReject(any()) } returns ApiResponse(
            0,
            "success",
            BatchReviewResult(processed = 1, failed = 0, failedIds = emptyList()),
        )

        val result = repository.batchReject(listOf(3L))

        assertEquals(1, result.processed)
        assertEquals(0, result.failed)
    }
}
