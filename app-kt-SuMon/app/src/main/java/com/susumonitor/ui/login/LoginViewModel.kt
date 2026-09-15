package com.susumonitor.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.susumonitor.data.ApiException
import com.susumonitor.data.model.CurrentUser
import com.susumonitor.data.repository.AuthRepository
import com.susumonitor.util.ErrorCodes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 登录页 UI 状态。 */
data class LoginUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val loggedInUser: CurrentUser? = null,
    // 系统仍待初始化首管理员时为 true，注册页据此展示一次性令牌输入框（批次 8）。
    val bootstrapPending: Boolean = false,
)

/**
 * 登录/注册 ViewModel：调用 AuthRepository，登录成功由导航层跳转。
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    /** 进入页面时查询首管理员初始化状态；失败按 false 降级不阻塞表单。 */
    fun refreshBootstrapStatus() {
        viewModelScope.launch {
            val pending = authRepository.getBootstrapPending()
            _uiState.value = _uiState.value.copy(bootstrapPending = pending)
        }
    }

    fun login(username: String, password: String) {
        if (_uiState.value.isLoading) return
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, loggedInUser = null)
        viewModelScope.launch {
            try {
                val user = authRepository.login(username.trim(), password)
                _uiState.value = _uiState.value.copy(isLoading = false, loggedInUser = user)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = friendlyMessage(e))
            }
        }
    }

    fun register(
        username: String,
        password: String,
        bootstrapToken: String? = null,
        onSuccess: () -> Unit,
    ) {
        if (_uiState.value.isLoading) return
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, loggedInUser = null)
        viewModelScope.launch {
            try {
                authRepository.register(username.trim(), password, bootstrapToken?.trim())
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "注册成功，请等待管理员审核后登录")
                onSuccess()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = friendlyMessage(e))
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = null, loggedInUser = null)
    }

    private fun friendlyMessage(e: Exception): String {
        val apiException = ApiException.from(e)
        return when (apiException) {
            is ApiException.Business -> when (apiException.code) {
                ErrorCodes.INVALID_USERNAME_OR_PASSWORD -> "用户名或密码错误"
                ErrorCodes.FORBIDDEN -> "账号未通过审核，暂无法登录"
                ErrorCodes.RESOURCE_CONFLICT -> "用户名已存在"
                ErrorCodes.AUTH_BOOTSTRAP_REQUIRED -> "系统尚未初始化管理员，请输入服务器启动日志中的一次性初始化令牌"
                ErrorCodes.AUTH_BOOTSTRAP_TOKEN_INVALID -> "初始化令牌无效，请核对服务器启动日志中的令牌"
                else -> apiException.message
            }
            is ApiException.Network -> "网络连接失败，请检查网络后重试"
            is ApiException.Parse -> "服务响应异常，请稍后重试"
        }
    }
}
