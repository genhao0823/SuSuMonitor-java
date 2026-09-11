package com.susumonitor.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.susumonitor.data.ApiException
import com.susumonitor.data.model.AiQaRequest
import com.susumonitor.data.model.AiToolCall
import com.susumonitor.data.model.AiUsage
import com.susumonitor.data.model.Server
import com.susumonitor.data.model.ServerQuery
import com.susumonitor.data.repository.AiRepository
import com.susumonitor.data.repository.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 会话消息角色。 */
enum class AiChatRole { USER, AI, ERROR }

/**
 * 一条会话消息。AI 消息附带工具调用审计 / 降级标记 / Token 用量；
 * 会话仅内存保留（后端 F2 为单轮无状态接口）。
 */
data class AiChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: AiChatRole,
    val text: String,
    val toolCalls: List<AiToolCall> = emptyList(),
    val modelUsed: Boolean? = null,
    val degraded: Boolean? = null,
    val usage: AiUsage? = null,
    val serverId: Long? = null,
)

/** AI 问答 UI 状态。 */
data class AiQaUiState(
    val messages: List<AiChatMessage> = emptyList(),
    val input: String = "",
    /** 提问目标服务器；null 表示全局。 */
    val selectedServerId: Long? = null,
    val servers: List<Server> = emptyList(),
    val isSending: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * AI 运维问答（F2）ViewModel：会话式本地消息列表 + 目标服务器选择 + 单轮问答调用。
 */
@HiltViewModel
class AiQaViewModel @Inject constructor(
    private val aiRepository: AiRepository,
    private val serverRepository: ServerRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AiQaUiState())
    val uiState: StateFlow<AiQaUiState> = _uiState.asStateFlow()

    init {
        loadServers()
    }

    fun updateInput(value: String) {
        _uiState.value = _uiState.value.copy(input = value)
    }

    fun selectServer(serverId: Long?) {
        _uiState.value = _uiState.value.copy(selectedServerId = serverId)
    }

    /** 清空本地会话。 */
    fun clearConversation() {
        _uiState.value = _uiState.value.copy(messages = emptyList(), errorMessage = null)
    }

    /** 发送提问；进行中禁止重复提交。 */
    fun send() {
        val state = _uiState.value
        val question = state.input.trim()
        if (question.isEmpty() || state.isSending) return
        _uiState.value = state.copy(
            input = "",
            isSending = true,
            errorMessage = null,
            messages = state.messages + AiChatMessage(
                role = AiChatRole.USER,
                text = question,
                serverId = state.selectedServerId,
            ),
        )
        viewModelScope.launch {
            try {
                val qa = aiRepository.ask(AiQaRequest(state.selectedServerId, question))
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    messages = _uiState.value.messages + AiChatMessage(
                        role = AiChatRole.AI,
                        text = qa.answer,
                        toolCalls = qa.toolCalls,
                        modelUsed = qa.modelUsed,
                        degraded = qa.degraded,
                        usage = qa.usage,
                        serverId = state.selectedServerId,
                    ),
                )
            } catch (e: Exception) {
                val message = when (e) {
                    is AiRepository.AiNotEnabledException -> e.message ?: "AI 功能未启用"
                    is AiRepository.AiRateLimitedException -> e.message ?: "AI 调用频率超限"
                    else -> ApiException.from(e).message ?: "请求失败"
                }
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    errorMessage = message,
                    messages = _uiState.value.messages +
                        AiChatMessage(role = AiChatRole.ERROR, text = message),
                )
            }
        }
    }

    private fun loadServers() {
        viewModelScope.launch {
            runCatching { serverRepository.list(ServerQuery(page = 1, pageSize = 100)) }
                .onSuccess { result ->
                    _uiState.value = _uiState.value.copy(servers = result.items)
                }
                // 服务器列表加载失败不阻塞问答，仅目标下拉为空
        }
    }
}
