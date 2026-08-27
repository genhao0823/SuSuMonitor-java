package com.susumonitor.ui.servers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.susumonitor.data.ApiException
import com.susumonitor.data.model.CreateServerRequest
import com.susumonitor.data.model.Server
import com.susumonitor.data.model.SshAuthTypeValues
import com.susumonitor.data.model.UpdateServerRequest
import com.susumonitor.data.repository.ServerRepository
import com.susumonitor.util.ErrorCodes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 服务器表单 UI 状态。 */
data class ServerFormUiState(
    val isLoading: Boolean = false,
    val isEditMode: Boolean = false,
    val errorMessage: String? = null,
    val saved: Boolean = false,
    // 表单字段
    val name: String = "",
    val host: String = "",
    val description: String = "",
    val sshHost: String = "",
    val sshPort: String = "22",
    val sshUser: String = "",
    val sshAuthType: String = SshAuthTypeValues.PASSWORD,
    val sshPassword: String = "",
    val sshPrivateKey: String = "",
    val sshPrivateKeyPassphrase: String = "",
    val fieldErrors: Map<String, String> = emptyMap(),
)

/**
 * 服务器表单 ViewModel：新建/编辑共用。
 * 编辑模式空凭据=保留原值（不发送）。
 */
@HiltViewModel
class ServerFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverRepository: ServerRepository,
) : ViewModel() {

    val serverId: Long = savedStateHandle.get<Long>("serverId") ?: 0L

    private val _uiState = MutableStateFlow(ServerFormUiState(isEditMode = serverId > 0))
    val uiState: StateFlow<ServerFormUiState> = _uiState.asStateFlow()

    init {
        if (serverId > 0) {
            loadServer()
        }
    }

    /** 编辑模式：加载现有服务器回填表单。 */
    private fun loadServer() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                val server = serverRepository.get(serverId)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    name = server.name,
                    host = server.host,
                    description = server.description ?: "",
                    sshHost = server.sshHost,
                    sshPort = server.sshPort.toString(),
                    sshUser = server.sshUser,
                    sshAuthType = server.sshAuthType,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = ApiException.from(e).message,
                )
            }
        }
    }

    fun onNameChange(v: String) = update { copy(name = v) }
    fun onHostChange(v: String) = update { copy(host = v) }
    fun onDescriptionChange(v: String) = update { copy(description = v) }
    fun onSshHostChange(v: String) = update { copy(sshHost = v) }
    fun onSshPortChange(v: String) = update { copy(sshPort = v.filter { it.isDigit() }) }
    fun onSshUserChange(v: String) = update { copy(sshUser = v) }
    fun onAuthTypeChange(v: String) = update { copy(sshAuthType = v) }
    fun onSshPasswordChange(v: String) = update { copy(sshPassword = v) }
    fun onSshPrivateKeyChange(v: String) = update { copy(sshPrivateKey = v) }
    fun onSshPrivateKeyPassphraseChange(v: String) = update { copy(sshPrivateKeyPassphrase = v) }

    /** 提交：校验 → 创建或更新。 */
    fun submit() {
        val errors = validate()
        if (errors.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(fieldErrors = errors)
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                if (serverId > 0) {
                    serverRepository.update(serverId, buildUpdateRequest())
                } else {
                    serverRepository.create(buildCreateRequest())
                }
                _uiState.value = _uiState.value.copy(isLoading = false, saved = true)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = friendlyMessage(e),
                )
            }
        }
    }

    private fun update(transform: ServerFormUiState.() -> ServerFormUiState) {
        _uiState.value = _uiState.value.transform().copy(fieldErrors = emptyMap())
    }

    private fun validate(): Map<String, String> {
        val s = _uiState.value
        val errors = mutableMapOf<String, String>()
        if (s.name.isBlank()) errors["name"] = "名称必填"
        if (s.host.isBlank()) errors["host"] = "主机必填"
        if (s.sshHost.isBlank()) errors["sshHost"] = "SSH 主机必填"
        val port = s.sshPort.toIntOrNull()
        if (port == null || port !in 1..65535) errors["sshPort"] = "端口 1-65535"
        if (s.sshUser.isBlank()) errors["sshUser"] = "SSH 用户必填"
        if (!s.isEditMode) {
            when (s.sshAuthType) {
                SshAuthTypeValues.PASSWORD -> if (s.sshPassword.isBlank()) errors["sshPassword"] = "密码必填"
                SshAuthTypeValues.PRIVATE_KEY -> if (s.sshPrivateKey.isBlank()) errors["sshPrivateKey"] = "私钥必填"
            }
        }
        return errors
    }

    private fun buildCreateRequest() = CreateServerRequest(
        name = _uiState.value.name.trim(),
        host = _uiState.value.host.trim(),
        description = _uiState.value.description.trim().ifEmpty { null },
        sshHost = _uiState.value.sshHost.trim(),
        sshPort = _uiState.value.sshPort.toInt(),
        sshUser = _uiState.value.sshUser.trim(),
        sshAuthType = _uiState.value.sshAuthType,
        sshPassword = _uiState.value.sshPassword.ifBlank { null },
        sshPrivateKey = _uiState.value.sshPrivateKey.ifBlank { null },
        sshPrivateKeyPassphrase = _uiState.value.sshPrivateKeyPassphrase.ifBlank { null },
    )

    private fun buildUpdateRequest() = UpdateServerRequest(
        name = _uiState.value.name.trim(),
        host = _uiState.value.host.trim(),
        description = _uiState.value.description.trim().ifEmpty { null },
        sshHost = _uiState.value.sshHost.trim(),
        sshPort = _uiState.value.sshPort.toInt(),
        sshUser = _uiState.value.sshUser.trim(),
        sshAuthType = _uiState.value.sshAuthType,
        // 编辑模式空凭据=保留原值（null 不发送）
        sshPassword = _uiState.value.sshPassword.ifBlank { null },
        sshPrivateKey = _uiState.value.sshPrivateKey.ifBlank { null },
        sshPrivateKeyPassphrase = _uiState.value.sshPrivateKeyPassphrase.ifBlank { null },
    )

    private fun friendlyMessage(e: Exception): String = when (val api = ApiException.from(e)) {
        is ApiException.Business -> when (api.code) {
            ErrorCodes.RESOURCE_CONFLICT -> "名称或主机已被占用"
            ErrorCodes.RESOURCE_NOT_FOUND -> "服务器不存在或已删除"
            ErrorCodes.FORBIDDEN -> "无权限操作"
            else -> api.message
        }
        is ApiException.Network -> "网络连接失败"
        is ApiException.Parse -> "服务响应异常"
    }
}
