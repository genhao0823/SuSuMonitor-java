package com.susumonitor.ui.servers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.susumonitor.data.ApiException
import com.susumonitor.data.model.AgentTokenVo
import com.susumonitor.data.model.Metrics
import com.susumonitor.data.model.Server
import com.susumonitor.data.model.ServerStatus
import com.susumonitor.data.model.SshHostKeyVo
import com.susumonitor.data.model.SshTestResult
import com.susumonitor.data.repository.MetricsRepository
import com.susumonitor.data.repository.ServerRepository
import com.susumonitor.service.MonitorForegroundService
import com.susumonitor.util.ErrorCodes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 服务器详情 UI 状态。 */
data class ServerDetailUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val server: Server? = null,
    val status: ServerStatus? = null,
    val latest: Metrics? = null,
    /** SSH 测试结果（测试后展示）。 */
    val sshTest: SshTestResult? = null,
    /** Agent Token 一次性明文（仅 register/rotate 后展示）。 */
    val agentToken: AgentTokenVo? = null,
    /** 主机指纹确认结果。 */
    val hostKeyResult: SshHostKeyVo? = null,
    /** 是否正在执行管理操作。 */
    val actionInProgress: Boolean = false,
)

/**
 * 服务器详情 ViewModel：信息 + 状态 + 最新指标 + 管理操作（SSH 测试/主机指纹/Agent Token）。
 */
@HiltViewModel
class ServerDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverRepository: ServerRepository,
    private val metricsRepository: MetricsRepository,
) : ViewModel() {

    val serverId: Long = savedStateHandle.get<Long>("serverId") ?: 0L

    private val _uiState = MutableStateFlow(ServerDetailUiState())
    val uiState: StateFlow<ServerDetailUiState> = _uiState.asStateFlow()

    init {
        load()
        // 订阅该服务器的实时指标
        viewModelScope.launch {
            MonitorForegroundService.metricsUpdates.collect { update ->
                if (update.serverId == serverId) {
                    _uiState.value = _uiState.value.copy(latest = update.metrics)
                }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                val server = serverRepository.get(serverId)
                val status = serverRepository.status(serverId)
                val latest = metricsRepository.latest(serverId)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    server = server,
                    status = status,
                    latest = latest,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = ApiException.from(e).message,
                )
            }
        }
    }

    /** 刷新状态快照（30s 轮询/手动刷新）。 */
    fun refreshStatus() {
        viewModelScope.launch {
            runCatching { serverRepository.status(serverId) }
                .onSuccess { status ->
                    _uiState.value = _uiState.value.copy(status = status)
                }
        }
    }

    /** SSH 测试（admin）。 */
    fun testSsh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(actionInProgress = true, errorMessage = null)
            try {
                val result = serverRepository.testSsh(serverId)
                _uiState.value = _uiState.value.copy(
                    actionInProgress = false,
                    sshTest = result,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    actionInProgress = false,
                    errorMessage = friendlyMessage(e),
                )
            }
        }
    }

    /** 确认/轮换主机指纹（admin）。 */
    fun confirmHostKey(expectedFingerprint: String, replace: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(actionInProgress = true, errorMessage = null)
            try {
                val result = serverRepository.confirmHostKey(serverId, expectedFingerprint, replace)
                _uiState.value = _uiState.value.copy(
                    actionInProgress = false,
                    hostKeyResult = result,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    actionInProgress = false,
                    errorMessage = friendlyMessage(e),
                )
            }
        }
    }

    /** 生成 Agent Token（admin）。 */
    fun registerAgentToken() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(actionInProgress = true, errorMessage = null)
            try {
                val token = serverRepository.registerAgentToken(serverId)
                _uiState.value = _uiState.value.copy(
                    actionInProgress = false,
                    agentToken = token,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    actionInProgress = false,
                    errorMessage = friendlyMessage(e),
                )
            }
        }
    }

    /** 轮换 Agent Token（admin）。 */
    fun rotateAgentToken() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(actionInProgress = true, errorMessage = null)
            try {
                val token = serverRepository.rotateAgentToken(serverId)
                _uiState.value = _uiState.value.copy(
                    actionInProgress = false,
                    agentToken = token,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    actionInProgress = false,
                    errorMessage = friendlyMessage(e),
                )
            }
        }
    }

    /** 吊销 Agent Token（admin）。 */
    fun revokeAgentToken() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(actionInProgress = true, errorMessage = null)
            try {
                serverRepository.revokeAgentToken(serverId)
                _uiState.value = _uiState.value.copy(
                    actionInProgress = false,
                    agentToken = null,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    actionInProgress = false,
                    errorMessage = friendlyMessage(e),
                )
            }
        }
    }

    /** 清除操作结果展示。 */
    fun clearResults() {
        _uiState.value = _uiState.value.copy(
            sshTest = null,
            agentToken = null,
            hostKeyResult = null,
            errorMessage = null,
        )
    }

    private fun friendlyMessage(e: Exception): String = when (val api = ApiException.from(e)) {
        is ApiException.Business -> when (api.code) {
            ErrorCodes.SSH_AUTHENTICATION_FAILED -> "SSH 认证失败，请检查凭据"
            ErrorCodes.SSH_CONNECTION_TIMEOUT -> "SSH 连接超时"
            ErrorCodes.SSH_HOST_KEY_NOT_CONFIRMED -> "主机密钥未确认，请先确认指纹"
            ErrorCodes.SSH_HOST_KEY_MISMATCH -> "主机密钥不匹配"
            ErrorCodes.SSH_CONNECTION_LIMIT_REACHED -> "SSH 连接数已达上限"
            ErrorCodes.SSH_CONNECTION_FAILED -> "SSH 连接失败"
            ErrorCodes.SSH_TARGET_FORBIDDEN -> "SSH 目标被禁止"
            ErrorCodes.RESOURCE_CONFLICT -> "状态冲突（凭据或指纹已变更），请刷新重试"
            ErrorCodes.RESOURCE_NOT_FOUND -> "服务器不存在或已删除"
            ErrorCodes.FORBIDDEN -> "无权限操作"
            else -> api.message
        }
        is ApiException.Network -> "网络连接失败"
        is ApiException.Parse -> "服务响应异常"
    }
}
