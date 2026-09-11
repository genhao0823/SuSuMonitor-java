package com.susumonitor.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.susumonitor.data.ApiException
import com.susumonitor.data.model.AiDiagnosis
import com.susumonitor.data.model.AiDiagnosisRequest
import com.susumonitor.data.repository.AiRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 诊断 UI 状态：表单 + 结果 + 加载态。 */
data class AiDiagnosisUiState(
    val serverId: Long = 0,
    val question: String = "",
    /** 历史时间窗（分钟），范围 5~1440。 */
    val historyMinutes: Int = 60,
    val isSubmitting: Boolean = false,
    val result: AiDiagnosis? = null,
    val errorMessage: String? = null,
    /** 未启用/限流等可在页面内展示的语义化错误。 */
    val notEnabled: Boolean = false,
)

/**
 * AI 只读诊断 ViewModel：时间窗 + 问题表单提交与结果展示。
 */
@HiltViewModel
class AiDiagnosisViewModel @Inject constructor(
    private val aiRepository: AiRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AiDiagnosisUiState())
    val uiState: StateFlow<AiDiagnosisUiState> = _uiState.asStateFlow()

    /** 从路由参数初始化目标服务器。 */
    fun init(serverId: Long) {
        if (_uiState.value.serverId == 0L && serverId > 0) {
            _uiState.value = _uiState.value.copy(serverId = serverId)
        }
    }

    fun updateQuestion(value: String) {
        _uiState.value = _uiState.value.copy(question = value)
    }

    fun selectHistoryMinutes(minutes: Int) {
        _uiState.value = _uiState.value.copy(historyMinutes = minutes)
    }

    /** 提交诊断；后端 provider 失败返回 200 + model_used=false 兜底摘要。 */
    fun submit() {
        val state = _uiState.value
        val question = state.question.trim()
        if (question.isEmpty() || state.isSubmitting || state.serverId <= 0) return
        _uiState.value = state.copy(isSubmitting = true, errorMessage = null, notEnabled = false)
        viewModelScope.launch {
            try {
                val result = aiRepository.diagnose(
                    AiDiagnosisRequest(
                        serverId = state.serverId,
                        question = question,
                        historyMinutes = state.historyMinutes.coerceIn(5, 1440),
                    ),
                )
                _uiState.value = _uiState.value.copy(isSubmitting = false, result = result)
            } catch (e: Exception) {
                val notEnabled = e is AiRepository.AiNotEnabledException
                val message = when (e) {
                    is AiRepository.AiNotEnabledException -> e.message ?: "AI 功能未启用"
                    is AiRepository.AiRateLimitedException -> e.message ?: "AI 调用频率超限"
                    else -> ApiException.from(e).message
                }
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    errorMessage = message,
                    notEnabled = notEnabled,
                )
            }
        }
    }
}
