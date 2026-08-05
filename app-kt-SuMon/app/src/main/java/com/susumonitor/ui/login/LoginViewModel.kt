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

    fun login(username: String, password: String) {
        if (_uiState.value.isLoading) return
        _uiState.value = LoginUiState(isLoading = true)
        viewModelScope.launch {
            try {
                val user = authRepository.login(username.trim(), password)
                _uiState.value = LoginUiState(loggedInUser = user)
            } catch (e: Exception) {
                _uiState.value = LoginUiState(errorMessage = friendlyMessage(e))
            }
        }
    }

    fun register(username: String, password: String, onSuccess: () -> Unit) {
        if (_uiState.value.isLoading) return
        _uiState.value = LoginUiState(isLoading = true)
        viewModelScope.launch {
            try {
                authRepository.register(username.trim(), password)
                _uiState.value = LoginUiState(errorMessage = "注册成功，请等待管理员审核后登录")
                onSuccess()
            } catch (e: Exception) {
                _uiState.value = LoginUiState(errorMessage = friendlyMessage(e))
            }
        }
    }

    fun clearError() {
        _uiState.value = LoginUiState()
    }

    private fun friendlyMessage(e: Exception): String {
        val apiException = ApiException.from(e)
        return when (apiException) {
            is ApiException.Business -> when (apiException.code) {
                ErrorCodes.INVALID_USERNAME_OR_PASSWORD -> "用户名或密码错误"
                ErrorCodes.FORBIDDEN -> "账号未通过审核，暂无法登录"
                ErrorCodes.RESOURCE_CONFLICT -> "用户名已存在"
                else -> apiException.message
            }
            is ApiException.Network -> "网络连接失败，请检查网络后重试"
            is ApiException.Parse -> "服务响应异常，请稍后重试"
        }
    }
}
