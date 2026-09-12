package com.susumonitor.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.susumonitor.data.ApiException
import com.susumonitor.data.model.AiHealthReport
import com.susumonitor.data.repository.AiRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 健康报告列表 UI 状态（F3）。 */
data class HealthReportsUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    /** 后端 susumonitor.ai.report.enabled=false 时端点 404，UI 展示未启用空态。 */
    val notEnabled: Boolean = false,
    val reports: List<AiHealthReport> = emptyList(),
    val total: Long = 0,
    val page: Int = 1,
    val hasMore: Boolean = false,
    /** 手动生成请求进行中（生成耗时较高，按钮期间禁止重复提交）。 */
    val isGenerating: Boolean = false,
    /** 生成成功的报告 ID（一次性导航到详情后由屏幕消费）。 */
    val generatedReportId: Long? = null,
)

/**
 * 健康报告列表 ViewModel：分页 + 下拉刷新 + 手动生成（可选日期，缺省后端取昨日）。
 * 同一 report_date 重复生成走后端 UPSERT 覆盖。
 */
@HiltViewModel
class HealthReportsViewModel @Inject constructor(
    private val aiRepository: AiRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HealthReportsUiState())
    val uiState: StateFlow<HealthReportsUiState> = _uiState.asStateFlow()

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || !state.hasMore) return
        loadPage(state.page + 1, reset = false)
    }

    fun refresh() {
        if (_uiState.value.isRefreshing || _uiState.value.isGenerating) return
        _uiState.value = _uiState.value.copy(isRefreshing = true, errorMessage = null)
        loadPage(1, reset = true, fromPull = true)
    }

    /** 消费"生成成功"事件（进入详情后清除，避免返回列表时重复导航）。 */
    fun consumeGeneratedReport() {
        if (_uiState.value.generatedReportId != null) {
            _uiState.value = _uiState.value.copy(generatedReportId = null)
        }
    }

    /**
     * 手动生成一份报告。
     *
     * @param reportDate YYYY-MM-DD；null 表示由后端取昨日（规避端侧时区与后端 UTC 日期边界错位）
     */
    fun generate(reportDate: String?) {
        if (_uiState.value.isGenerating) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isGenerating = true, errorMessage = null)
            try {
                val report = aiRepository.generateHealthReport(reportDate)
                _uiState.value = _uiState.value.copy(
                    isGenerating = false,
                    generatedReportId = report.id,
                )
                // 生成后刷新列表（UPSERT 可能覆盖旧行），再由屏幕导航到详情
                _uiState.value = _uiState.value.copy(isRefreshing = true)
                loadPage(1, reset = true, fromPull = true)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isGenerating = false,
                    errorMessage = generateErrorMessage(e),
                )
            }
        }
    }

    private fun loadPage(page: Int, reset: Boolean, fromPull: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = !fromPull,
                isRefreshing = fromPull,
                errorMessage = null,
            )
            try {
                val result = aiRepository.listHealthReports(page = page, pageSize = PAGE_SIZE)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    notEnabled = false,
                    reports = if (reset) result.items else _uiState.value.reports + result.items,
                    total = result.total,
                    page = page,
                    hasMore = (page.toLong() * PAGE_SIZE) < result.total,
                )
            } catch (e: Exception) {
                val notEnabled = e is AiRepository.AiNotEnabledException
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    notEnabled = notEnabled,
                    errorMessage = if (notEnabled) "健康报告功能未在后端启用" else ApiException.from(e).message,
                )
            }
        }
    }

    private fun generateErrorMessage(e: Exception): String {
        return when (e) {
            is AiRepository.AiNotEnabledException -> "健康报告功能未在后端启用"
            is AiRepository.AiRateLimitedException -> "AI 请求过于频繁或今日额度已用尽,请稍后再试"
            else -> ApiException.from(e).message ?: "报告生成失败,请稍后重试"
        }
    }

    init {
        loadPage(1, reset = true)
    }

    private companion object {
        const val PAGE_SIZE = 20
    }
}
