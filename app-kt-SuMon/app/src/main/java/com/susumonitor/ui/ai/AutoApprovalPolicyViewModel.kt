package com.susumonitor.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.susumonitor.data.ApiException
import com.susumonitor.data.model.AutoApprovalPolicy
import com.susumonitor.data.repository.CommandRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 自动审批策略 UI 状态。 */
data class AutoApprovalPolicyUiState(
    val isLoading: Boolean = true,
    /** 策略开关（编辑副本）。 */
    val enabled: Boolean = false,
    /** 风险阈值（编辑副本）：low 仅低风险 / medium 中低风险。 */
    val maxRiskLevel: String = "medium",
    /** 保存请求进行中。 */
    val isSaving: Boolean = false,
    /** 保存成功提示（短暂展示）。 */
    val savedMessage: String? = null,
    val errorMessage: String? = null,
    val notEnabled: Boolean = false,
    /** 最近一次保存后的快照时间。 */
    val updatedAt: String? = null,
)

/**
 * 自动审批策略 ViewModel：读取/更新实例级单行策略。
 * 策略仅影响 AI 建议来源命令；手动命令始终人工审批。
 */
@HiltViewModel
class AutoApprovalPolicyViewModel @Inject constructor(
    private val commandRepository: CommandRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AutoApprovalPolicyUiState())
    val uiState: StateFlow<AutoApprovalPolicyUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                applyPolicy(commandRepository.autoApprovalPolicy())
            } catch (e: Exception) {
                handleError(e, isLoading = true)
            }
        }
    }

    fun setEnabled(value: Boolean) {
        _uiState.value = _uiState.value.copy(enabled = value)
    }

    fun setMaxRiskLevel(value: String) {
        if (value == "low" || value == "medium") {
            _uiState.value = _uiState.value.copy(maxRiskLevel = value)
        }
    }

    fun save() {
        val state = _uiState.value
        if (state.isSaving) return
        viewModelScope.launch {
            _uiState.value = state.copy(isSaving = true, savedMessage = null, errorMessage = null)
            try {
                val latest = commandRepository.updateAutoApprovalPolicy(
                    state.enabled,
                    state.maxRiskLevel,
                )
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    enabled = latest.enabled,
                    maxRiskLevel = latest.maxRiskLevel,
                    updatedAt = latest.updatedAt,
                    savedMessage = if (latest.enabled) "自动审批已开启" else "自动审批已关闭，恢复人工审批",
                )
            } catch (e: Exception) {
                handleError(e, isLoading = false)
            }
        }
    }

    fun consumeSavedMessage() {
        _uiState.value = _uiState.value.copy(savedMessage = null)
    }

    private fun applyPolicy(policy: AutoApprovalPolicy) {
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            enabled = policy.enabled,
            maxRiskLevel = if (policy.maxRiskLevel == "low") "low" else "medium",
            updatedAt = policy.updatedAt,
        )
    }

    private fun handleError(e: Exception, isLoading: Boolean) {
        val notEnabled = e is CommandRepository.CommandDomainNotEnabledException
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            isSaving = false,
            notEnabled = notEnabled,
            errorMessage = if (notEnabled) {
                "命令执行功能未启用"
            } else {
                ApiException.from(e).message
            },
        )
        if (isLoading) {
            // 读取失败保持默认禁用视图，不额外清空编辑副本
        }
    }
}
