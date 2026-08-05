package com.susumonitor.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.susumonitor.data.ApiException
import com.susumonitor.data.model.AlertRule
import com.susumonitor.data.model.CreateAlertRuleRequest
import com.susumonitor.data.model.Server
import com.susumonitor.data.model.ServerQuery
import com.susumonitor.data.model.UpdateAlertRuleRequest
import com.susumonitor.data.repository.AlertRepository
import com.susumonitor.data.repository.ServerRepository
import com.susumonitor.util.ErrorCodes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 告警规则 UI 状态。 */
data class AlertRulesUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val rules: List<AlertRule> = emptyList(),
    val servers: List<Server> = emptyList(),
)

/**
 * 告警规则 ViewModel：列表 + CRUD + 启停（admin）。
 */
@HiltViewModel
class AlertRulesViewModel @Inject constructor(
    private val alertRepository: AlertRepository,
    private val serverRepository: ServerRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlertRulesUiState())
    val uiState: StateFlow<AlertRulesUiState> = _uiState.asStateFlow()

    init {
        loadAll()
    }

    fun loadAll() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                val rules = alertRepository.listRules()
                val servers = runCatching {
                    serverRepository.list(ServerQuery(pageSize = 100)).items
                }.getOrDefault(emptyList())
                _uiState.value = _uiState.value.copy(isLoading = false, rules = rules, servers = servers)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = ApiException.from(e).message,
                )
            }
        }
    }

    /** 创建规则（admin）。 */
    fun createRule(request: CreateAlertRuleRequest, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                val rule = alertRepository.createRule(request)
                _uiState.value = _uiState.value.copy(rules = listOf(rule) + _uiState.value.rules)
                onDone()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = friendlyMessage(e))
            }
        }
    }

    /** 更新规则（admin）。 */
    fun updateRule(id: Long, request: UpdateAlertRuleRequest, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                val updated = alertRepository.updateRule(id, request)
                _uiState.value = _uiState.value.copy(
                    rules = _uiState.value.rules.map { if (it.id == id) updated else it },
                )
                onDone()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = friendlyMessage(e))
            }
        }
    }

    /** 启停切换（admin）。 */
    fun toggleEnabled(rule: AlertRule) {
        viewModelScope.launch {
            try {
                val updated = alertRepository.updateRule(
                    rule.id,
                    UpdateAlertRuleRequest(
                        thresholdValue = rule.thresholdValue,
                        level = rule.level,
                        enabled = !rule.enabled,
                        confirmCount = rule.confirmCount,
                        notifyEmail = rule.notifyEmail,
                        notifyDingtalk = rule.notifyDingtalk,
                        notifyWebhook = rule.notifyWebhook,
                    ),
                )
                _uiState.value = _uiState.value.copy(
                    rules = _uiState.value.rules.map { if (it.id == rule.id) updated else it },
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = friendlyMessage(e))
            }
        }
    }

    /** 删除规则（admin）。 */
    fun deleteRule(id: Long) {
        viewModelScope.launch {
            try {
                alertRepository.deleteRule(id)
                _uiState.value = _uiState.value.copy(
                    rules = _uiState.value.rules.filterNot { it.id == id },
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = friendlyMessage(e))
            }
        }
    }

    /** 服务器名解析（全局规则显示"全局"）。 */
    fun serverName(rule: AlertRule): String {
        if (rule.serverId == null) return "全局"
        return _uiState.value.servers.firstOrNull { it.id == rule.serverId }?.name
            ?: "#${rule.serverId}"
    }

    private fun friendlyMessage(e: Exception): String = when (val api = ApiException.from(e)) {
        is ApiException.Business -> when (api.code) {
            ErrorCodes.FORBIDDEN -> "无权限操作"
            ErrorCodes.RESOURCE_NOT_FOUND -> "规则不存在或已删除"
            ErrorCodes.INVALID_REQUEST_PARAMETER -> "参数不合法"
            else -> api.message
        }
        is ApiException.Network -> "网络连接失败"
        is ApiException.Parse -> "服务响应异常"
    }
}
