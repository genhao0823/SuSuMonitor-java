package com.susumonitor.ui.system

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.susumonitor.data.ApiException
import com.susumonitor.data.model.ConsumeStats
import com.susumonitor.data.model.QueueBacklog
import com.susumonitor.data.repository.SystemRepository
import com.susumonitor.ui.components.EmptyState
import com.susumonitor.ui.components.ErrorState
import com.susumonitor.ui.components.LoadingState
import com.susumonitor.ui.theme.StatusCritical
import com.susumonitor.ui.theme.StatusOnline
import com.susumonitor.ui.theme.StatusWarning
import com.susumonitor.util.TimeFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** RabbitMQ 监控 UI 状态。 */
data class SystemMonitorUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val queues: List<QueueBacklog> = emptyList(),
    val consumers: List<ConsumeStats> = emptyList(),
    val errorMessage: String? = null,
)

/** RabbitMQ 监控 ViewModel：队列积压 + 消费者统计，下拉刷新。 */
@HiltViewModel
class SystemMonitorViewModel @Inject constructor(
    private val systemRepository: SystemRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SystemMonitorUiState())
    val uiState: StateFlow<SystemMonitorUiState> = _uiState.asStateFlow()

    init {
        load(fromPull = false)
    }

    fun refresh() {
        if (_uiState.value.isRefreshing) return
        load(fromPull = true)
    }

    private fun load(fromPull: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = !fromPull,
                isRefreshing = fromPull,
                errorMessage = null,
            )
            try {
                val queues = systemRepository.rabbitmqQueues()
                val consumers = runCatching { systemRepository.rabbitmqConsumers() }.getOrDefault(emptyList())
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    queues = queues,
                    consumers = consumers,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    errorMessage = ApiException.from(e).message,
                )
            }
        }
    }
}

/**
 * RabbitMQ 系统监控页（admin）：队列积压深度（超阈值标红）+ 消费者吞吐统计。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemMonitorScreen(
    onBack: () -> Unit = {},
    viewModel: SystemMonitorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RabbitMQ 监控") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) { Text("←") }
                },
                actions = {
                    TextButton(onClick = viewModel::refresh) { Text("刷新") }
                },
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            when {
                uiState.isLoading -> LoadingState()
                uiState.errorMessage != null && uiState.queues.isEmpty() ->
                    ErrorState(
                        message = uiState.errorMessage.orEmpty(),
                        onRetry = viewModel::refresh,
                    )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text("队列积压", style = MaterialTheme.typography.titleMedium)
                    }
                    if (uiState.queues.isEmpty()) {
                        item { EmptyState(text = "暂无队列数据") }
                    }
                    items(uiState.queues, key = { "${it.queue}-${it.type}" }) { queue ->
                        QueueCard(queue)
                    }
                    item {
                        Text(
                            text = "消费者统计",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (uiState.consumers.isEmpty()) {
                        item { EmptyState(text = "暂无消费者数据") }
                    }
                    items(uiState.consumers, key = { it.consumer }) { consumer ->
                        ConsumerCard(consumer)
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueCard(queue: QueueBacklog) {
    val overThreshold = queue.messages > queue.warnThreshold
    val color = when {
        queue.error || overThreshold -> StatusCritical
        queue.messages > queue.warnThreshold / 2 -> StatusWarning
        else -> StatusOnline
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = queue.queue,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${queue.messages} 条",
                    style = MaterialTheme.typography.titleSmall,
                    color = color,
                )
            }
            Text(
                text = buildString {
                    append("类型 ${queue.type} · 阈值 ${queue.warnThreshold}")
                    if (overThreshold) append(" · 已超阈值")
                },
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
            Text(
                text = "检查于 ${TimeFormatter.formatLocal(queue.checkedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConsumerCard(consumer: ConsumeStats) {
    val failureColor =
        if (consumer.failureRate > 0.05) StatusCritical else MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = consumer.consumer,
                style = MaterialTheme.typography.titleSmall,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(6.dp),
                    )
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatCell("总消费", consumer.totalCount.toString())
                StatCell("窗口", "${consumer.consumedWindow}/${consumer.consumedWindow + consumer.failedWindow}")
                StatCell("平均耗时", "${consumer.avgMs}ms")
                StatCell("失败率", "${"%.1f".format(consumer.failureRate * 100)}%", failureColor)
            }
            consumer.lastSampleAt?.let {
                Text(
                    text = "最近采样 ${TimeFormatter.formatLocal(it)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color? = null) {
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
