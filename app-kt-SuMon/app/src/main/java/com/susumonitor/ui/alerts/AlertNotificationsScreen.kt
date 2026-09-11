package com.susumonitor.ui.alerts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.susumonitor.data.ApiException
import com.susumonitor.data.model.AlertNotification
import com.susumonitor.data.repository.AlertRepository
import com.susumonitor.ui.components.EmptyState
import com.susumonitor.ui.components.ErrorState
import com.susumonitor.ui.components.LoadingState
import com.susumonitor.ui.theme.StatusCritical
import com.susumonitor.ui.theme.StatusOffline
import com.susumonitor.ui.theme.StatusOnline
import com.susumonitor.ui.theme.StatusWarning
import com.susumonitor.util.TimeFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 通知投递 UI 状态。 */
data class AlertNotificationsUiState(
    val isLoading: Boolean = true,
    val notifications: List<AlertNotification> = emptyList(),
    val errorMessage: String? = null,
)

/** 告警通知投递 ViewModel：加载单条告警的外发投递记录。 */
@HiltViewModel
class AlertNotificationsViewModel @Inject constructor(
    private val alertRepository: AlertRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlertNotificationsUiState())
    val uiState: StateFlow<AlertNotificationsUiState> = _uiState.asStateFlow()

    fun load(recordId: Long) {
        if (_uiState.value.notifications.isNotEmpty()) return
        viewModelScope.launch {
            try {
                val notifications = alertRepository.notifications(recordId)
                _uiState.value = _uiState.value.copy(isLoading = false, notifications = notifications)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = ApiException.from(e).message,
                )
            }
        }
    }
}

/**
 * 告警通知投递记录页：渠道 / 状态 / 重试次数 / 最近错误。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertNotificationsScreen(
    recordId: Long,
    onBack: () -> Unit = {},
    viewModel: AlertNotificationsViewModel = hiltViewModel(),
) {
    LaunchedEffect(recordId) { viewModel.load(recordId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通知投递记录") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) { Text("←") }
                },
            )
        },
    ) { innerPadding ->
        when {
            uiState.isLoading -> LoadingState(modifier = Modifier.padding(innerPadding))
            uiState.errorMessage != null ->
                ErrorState(
                    message = uiState.errorMessage.orEmpty(),
                    onRetry = { viewModel.load(recordId) },
                    modifier = Modifier.padding(innerPadding),
                )
            uiState.notifications.isEmpty() -> EmptyState(
                text = "该告警未配置外发通知\n（可在告警规则中配置邮件 / 钉钉 / Webhook）",
                modifier = Modifier.padding(innerPadding),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(uiState.notifications, key = { it.id }) { notification ->
                    NotificationCard(notification)
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(notification: AlertNotification) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = channelLabel(notification.channel),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = statusLabel(notification.status),
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor(notification.status),
                )
            }
            Text(
                text = "重试 ${notification.attempts} 次 · 更新于 ${TimeFormatter.formatLocal(notification.updatedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            notification.nextAttemptAt?.let {
                Text(
                    text = "下次重试：${TimeFormatter.formatLocal(it)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusWarning,
                )
            }
            notification.lastError?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = "最近错误：$it",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusCritical,
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun channelLabel(channel: String): String = when (channel.trim()) {
    "email" -> "邮件"
    "dingtalk" -> "钉钉"
    "webhook" -> "Webhook"
    else -> channel.trim()
}

private fun statusLabel(status: String): String = when (status) {
    "pending" -> "待发送"
    "sent" -> "已送达"
    "failed" -> "失败"
    else -> status
}

private fun statusColor(status: String): androidx.compose.ui.graphics.Color = when (status) {
    "pending" -> StatusWarning
    "sent" -> StatusOnline
    "failed" -> StatusCritical
    else -> StatusOffline
}
