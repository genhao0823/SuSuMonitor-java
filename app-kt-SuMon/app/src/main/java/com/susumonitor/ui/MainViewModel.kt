package com.susumonitor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.susumonitor.data.SessionStore
import com.susumonitor.data.model.Session
import com.susumonitor.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 会话路由状态：应用启动时读取持久化会话决定起始页；
 * 登出时清空会话并停止监控服务。
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    sessionStore: SessionStore,
) : ViewModel() {

    /** null = 会话尚未加载完成。 */
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    /** 告警通知点击深链事件（AppNavHost 收集后切换到告警 Tab）。 */
    private val _openAlertsRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openAlertsRequest: SharedFlow<Unit> = _openAlertsRequest.asSharedFlow()

    init {
        viewModelScope.launch {
            sessionStore.session.collect { _session.value = it }
        }
    }

    /** 通知点击：请求打开告警 Tab。 */
    fun requestOpenAlerts() {
        _openAlertsRequest.tryEmit(Unit)
    }

    /** 登出：调用后端 + 清空本地会话（UI 侧停止监控服务后调用）。 */
    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
        }
    }
}
