package com.susumonitor.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.susumonitor.data.SessionStore
import com.susumonitor.data.model.CurrentUser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 设置页 UI 状态。 */
data class SettingsUiState(
    val user: CurrentUser? = null,
    /** 通知开关（本地持久化：是否启用告警推送）。 */
    val notificationsEnabled: Boolean = true,
)

/**
 * 设置页 ViewModel：展示用户信息 + 通知开关（DataStore 持久化）。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val sessionStore: SessionStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            sessionStore.session.collect { session ->
                _uiState.value = _uiState.value.copy(user = session?.user)
            }
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(notificationsEnabled = enabled)
        // MVP：通知开关状态暂存内存；后续可接入 DataStore 持久化与前台服务联动
    }
}
