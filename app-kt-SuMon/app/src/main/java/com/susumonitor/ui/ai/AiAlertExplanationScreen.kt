package com.susumonitor.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.susumonitor.data.ApiException
import com.susumonitor.data.model.AiAlertExplanation
import com.susumonitor.data.model.AlertNotification
import com.susumonitor.data.repository.AiRepository
import com.susumonitor.data.repository.AlertRepository
import com.susumonitor.ui.components.EmptyState
import com.susumonitor.ui.components.ErrorState
import com.susumonitor.ui.components.LoadingState
import com.susumonitor.util.TimeFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 告警 AI 解释 UI 状态。 */
data class AiAlertExplanationUiState(
    val isLoading: Boolean = true,
    val explanation: AiAlertExplanation? = null,
    val errorMessage: String? = null,
    val notEnabled: Boolean = false,
)

/**
 * 告警智能解释 ViewModel：加载指定记录的 AI 根因分析（F1 异步生成后回看）。
 */
@HiltViewModel
class AiAlertExplanationViewModel @Inject constructor(
    private val aiRepository: AiRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AiAlertExplanationUiState())
    val uiState: StateFlow<AiAlertExplanationUiState> = _uiState.asStateFlow()

    fun load(recordId: Long) {
        if (_uiState.value.explanation?.recordId == recordId) return
        viewModelScope.launch {
            _uiState.value = AiAlertExplanationUiState(isLoading = true)
            try {
                val explanation = aiRepository.alertExplanation(recordId)
                _uiState.value = _uiState.value.copy(isLoading = false, explanation = explanation)
            } catch (e: Exception) {
                val notEnabled = e is AiRepository.AiNotEnabledException
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    notEnabled = notEnabled,
                    errorMessage = when (e) {
                        is AiRepository.AiNotEnabledException -> "解释尚未生成或 AI 功能未启用（触发后会异步生成，可稍后重试）"
                        is AiRepository.AiRateLimitedException -> e.message ?: "AI 调用频率超限"
                        else -> ApiException.from(e).message
                    },
                )
            }
        }
    }
}

/**
 * 告警 AI 解释页：概要 / 可能原因 / 影响 / 建议 / 边界声明。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAlertExplanationScreen(
    recordId: Long,
    onBack: () -> Unit = {},
    viewModel: AiAlertExplanationViewModel = hiltViewModel(),
) {
    LaunchedEffect(recordId) { viewModel.load(recordId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 告警解释") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) { Text("←") }
                },
            )
        },
    ) { innerPadding ->
        when {
            uiState.isLoading -> LoadingState(modifier = Modifier.padding(innerPadding))
            uiState.notEnabled -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "解释尚未生成",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "AI 解释在告警触发后异步生成，\n历史告警可能没有解释记录，可稍后重试",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                androidx.compose.material3.TextButton(onClick = { viewModel.load(recordId) }) {
                    Text("重试")
                }
            }
            uiState.errorMessage != null && uiState.explanation == null ->
                ErrorState(
                    message = uiState.errorMessage.orEmpty(),
                    onRetry = { viewModel.load(recordId) },
                    modifier = Modifier.padding(innerPadding),
                )
            else -> {
                val explanation = uiState.explanation ?: return@Scaffold
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("分析概要", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = explanation.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            explanation.createdAt?.let {
                                Text(
                                    text = "生成于 ${TimeFormatter.formatLocal(it)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                    StringListSection("可能原因", explanation.possibleCauses)
                    StringListSection("潜在影响", explanation.impact)
                    StringListSection("排查建议", explanation.suggestions)
                    StringListSection("边界声明", explanation.limitations)
                    explanation.usage?.let { usage ->
                        Text(
                            text = "${explanation.provider}/${explanation.model} · tokens ${usage.totalTokens}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StringListSection(title: String, items: List<String>) {
    if (items.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Column(modifier = Modifier.padding(top = 6.dp)) {
                items.forEach { item ->
                    Text(
                        text = "· $item",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}
