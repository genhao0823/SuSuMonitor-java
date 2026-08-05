package com.susumonitor.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.susumonitor.data.ApiException
import com.susumonitor.data.model.AdminUserQuery
import com.susumonitor.data.model.AdminUserVo
import com.susumonitor.data.model.BatchReviewResult
import com.susumonitor.data.model.ReviewStatusValues
import com.susumonitor.data.repository.AdminRepository
import com.susumonitor.util.ErrorCodes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 审核状态 Tab。 */
enum class ReviewTab(val label: String, val value: String?) {
    PENDING("待审核", ReviewStatusValues.PENDING),
    APPROVED("已通过", ReviewStatusValues.APPROVED),
    REJECTED("已拒绝", ReviewStatusValues.REJECTED),
}

/** 用户审核 UI 状态。 */
data class AdminUsersUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val users: List<AdminUserVo> = emptyList(),
    val total: Long = 0,
    val page: Int = 1,
    val pageSize: Int = 20,
    val hasMore: Boolean = false,
    val tab: ReviewTab = ReviewTab.PENDING,
    val keyword: String = "",
    val selectedIds: Set<Long> = emptySet(),
    /** 批量操作失败明细。 */
    val batchFailed: BatchReviewResult? = null,
)

/**
 * 用户审核 ViewModel：状态 Tab + 搜索 + 分页 + 单条/批量通过拒绝（admin）。
 */
@HiltViewModel
class AdminUsersViewModel @Inject constructor(
    private val adminRepository: AdminRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminUsersUiState())
    val uiState: StateFlow<AdminUsersUiState> = _uiState.asStateFlow()

    fun selectTab(tab: ReviewTab) {
        _uiState.value = _uiState.value.copy(tab = tab, selectedIds = emptySet())
        loadFirstPage()
    }

    fun onKeywordChange(keyword: String) {
        _uiState.value = _uiState.value.copy(keyword = keyword)
        loadFirstPage()
    }

    fun toggleSelect(id: Long) {
        val current = _uiState.value.selectedIds
        _uiState.value = _uiState.value.copy(
            selectedIds = if (id in current) current - id else current + id,
        )
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedIds = emptySet())
    }

    fun loadFirstPage() {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, page = 1)
        loadPage(1, reset = true)
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || !state.hasMore) return
        loadPage(state.page + 1, reset = false)
    }

    /** 单条通过。 */
    fun approveUser(id: Long) {
        viewModelScope.launch {
            try {
                adminRepository.approveUser(id)
                removeFromList(id)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = friendlyMessage(e))
            }
        }
    }

    /** 单条拒绝。 */
    fun rejectUser(id: Long) {
        viewModelScope.launch {
            try {
                adminRepository.rejectUser(id)
                removeFromList(id)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = friendlyMessage(e))
            }
        }
    }

    /** 批量通过。 */
    fun batchApprove(ids: List<Long>) {
        viewModelScope.launch {
            try {
                val result = adminRepository.batchApprove(ids)
                handleBatchResult(result, ids)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = friendlyMessage(e))
            }
        }
    }

    /** 批量拒绝。 */
    fun batchReject(ids: List<Long>) {
        viewModelScope.launch {
            try {
                val result = adminRepository.batchReject(ids)
                handleBatchResult(result, ids)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = friendlyMessage(e))
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null, batchFailed = null)
    }

    private fun handleBatchResult(result: BatchReviewResult, attempted: List<Long>) {
        val failedSet = result.failedIds.toSet()
        val succeeded = attempted.filterNot { it in failedSet }
        _uiState.value = _uiState.value.copy(
            users = _uiState.value.users.filterNot { it.id in succeeded },
            total = (_uiState.value.total - succeeded.size).coerceAtLeast(0),
            selectedIds = emptySet(),
            batchFailed = if (result.failed > 0) result else null,
        )
    }

    private fun removeFromList(id: Long) {
        _uiState.value = _uiState.value.copy(
            users = _uiState.value.users.filterNot { it.id == id },
            total = (_uiState.value.total - 1).coerceAtLeast(0),
            selectedIds = _uiState.value.selectedIds - id,
        )
    }

    private fun loadPage(page: Int, reset: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                val result = adminRepository.listUsers(
                    AdminUserQuery(
                        status = _uiState.value.tab.value,
                        keyword = _uiState.value.keyword.trim().ifEmpty { null },
                        page = page,
                        pageSize = _uiState.value.pageSize,
                    ),
                )
                val users = if (reset) result.items else _uiState.value.users + result.items
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    users = users,
                    total = result.total,
                    page = page,
                    hasMore = (page * _uiState.value.pageSize) < result.total,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = ApiException.from(e).message,
                )
            }
        }
    }

    private fun friendlyMessage(e: Exception): String = when (val api = ApiException.from(e)) {
        is ApiException.Business -> when (api.code) {
            ErrorCodes.RESOURCE_CONFLICT -> "用户已被审核"
            ErrorCodes.RESOURCE_NOT_FOUND -> "用户不存在"
            ErrorCodes.FORBIDDEN -> "无权限操作"
            ErrorCodes.INVALID_REQUEST_PARAMETER -> "参数不合法"
            else -> api.message
        }
        is ApiException.Network -> "网络连接失败"
        is ApiException.Parse -> "服务响应异常"
    }
}
