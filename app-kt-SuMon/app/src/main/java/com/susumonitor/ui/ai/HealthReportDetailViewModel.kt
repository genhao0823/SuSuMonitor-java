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

/** 健康报告详情 UI 状态。 */
data class HealthReportDetailUiState(
    val isLoading: Boolean = true,
    val report: AiHealthReport? = null,
    val errorMessage: String? = null,
    val notEnabled: Boolean = false,
)

/** 健康报告详情 ViewModel：按 ID 加载完整报告（列表行已含 facts，二次进入仍以详情接口为准）。 */
@HiltViewModel
class HealthReportDetailViewModel @Inject constructor(
    private val aiRepository: AiRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HealthReportDetailUiState())
    val uiState: StateFlow<HealthReportDetailUiState> = _uiState.asStateFlow()

    fun load(reportId: Long) {
        if (_uiState.value.report?.id == reportId) return
        viewModelScope.launch {
            _uiState.value = HealthReportDetailUiState(isLoading = true)
            try {
                val report = aiRepository.healthReport(reportId)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    report = report,
                    notEnabled = false,
                )
            } catch (e: Exception) {
                val notEnabled = e is AiRepository.AiNotEnabledException
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    notEnabled = notEnabled,
                    errorMessage = if (notEnabled) "健康报告功能未在后端启用" else ApiException.from(e).message,
                )
            }
        }
    }
}
