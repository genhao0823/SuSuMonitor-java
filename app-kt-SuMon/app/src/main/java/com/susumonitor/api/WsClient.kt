package com.susumonitor.api

import com.susumonitor.data.WsMessage
import com.susumonitor.data.WsMessageParser
import com.susumonitor.data.model.WsFrame
import com.susumonitor.data.model.WsFrameType
import com.susumonitor.data.repository.SystemRepository
import com.susumonitor.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Monitor WebSocket 客户端（对齐 websocket-protocol.md v1.3）：
 *
 * 1. 经 [SystemRepository.monitorTicket] 获取 30s 一次性 ticket（凭据不进 URL）
 * 2. 连接 [Constants.WS_URL]，登录后 `metrics.subscribe` 订阅服务器
 * 3. 收到 `metrics.update` / `alert.push` / `server.status.update` 经 [WsMessageParser] 分发
 * 4. 断线后指数退避重连，重连前重新取 ticket
 *
 * 线程模型：OkHttp WebSocket 回调在 OkHttp 线程，解析后经 [messages] SharedFlow 发布，
 * 消费者自行切换到所需上下文。
 */
@Singleton
class WsClient @Inject constructor(
    private val systemRepository: SystemRepository,
    private val okHttpClient: OkHttpClient,
    private val parser: WsMessageParser,
    private val json: Json,
) {

    /** 连接状态。 */
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** 解析后的业务消息流（metrics.update / alert.push / server.status.update / error）。 */
    private val _messages = MutableSharedFlow<WsMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<WsMessage> = _messages.asSharedFlow()

    /** 当前订阅的服务器 ID 集合（去重）。 */
    private val subscribedServers = LinkedHashSet<Long>()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var running = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectJob: Job? = null

    /**
     * WebSocket 专用客户端：派生自注入的 [okHttpClient] 并加 30s 应用层 Ping。
     * nginx 空闲断开（半开连接）后 TCP 层无感知，Ping 无响应会触发 onFailure → 自动重连。
     */
    private val webSocketClient: OkHttpClient =
        okHttpClient.newBuilder().pingInterval(30, TimeUnit.SECONDS).build()

    /**
     * 启动连接。幂等：已连接/连接中时忽略。
     * @param serverIds 需要订阅的服务器 ID 列表
     */
    fun start(serverIds: List<Long>) {
        if (running) {
            resubscribe(serverIds)
            return
        }
        running = true
        subscribedServers.clear()
        subscribedServers.addAll(serverIds)
        connectJob = scope.launch { connectWithRetry() }
    }

    /** 追加订阅服务器（连接中则发送 subscribe 帧）。 */
    fun subscribe(serverId: Long) {
        if (subscribedServers.add(serverId)) {
            sendSubscribe(serverId)
        }
    }

    /** 追加批量订阅。 */
    fun resubscribe(serverIds: List<Long>) {
        serverIds.forEach { subscribe(it) }
    }

    /**
     * 确保已连接并订阅指定服务器（终端页用）。
     * 已连接则订阅并返回 true；未连接则启动连接（异步），返回 false 由调用方重试。
     */
    fun ensureConnected(serverId: Long): Boolean {
        if (_connectionState.value == ConnectionState.CONNECTED) {
            subscribe(serverId)
            return true
        }
        if (!running) {
            start(listOf(serverId))
        } else {
            subscribe(serverId)
        }
        return false
    }

    /** 停止连接并释放资源。 */
    fun stop() {
        running = false
        connectJob?.cancel()
        webSocket?.close(1000, "client stopped")
        webSocket = null
        subscribedServers.clear()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /** 指数退避重连循环：首次立即连接，失败后 1s/2s/4s…上限 30s，轻微抖动。 */
    private suspend fun connectWithRetry() {
        var attempt = 0
        while (running) {
            try {
                connectOnce()
                // 连接已建立（onOpen 后才返回），等待失败通知继续循环
                while (running && _connectionState.value == ConnectionState.CONNECTED) {
                    delay(500)
                }
                if (!running) break
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
            attempt = min(attempt + 1, 5)
            val backoffMs = (1000L shl attempt) + (attempt * 100L) // 指数 + 等抖动
            _connectionState.value = ConnectionState.RECONNECTING
            delay(backoffMs)
        }
    }

    /** 单次连接：取 ticket → 建连（onOpen 返回后连接成功）。 */
    private suspend fun connectOnce() {
        _connectionState.value = ConnectionState.CONNECTING
        val ticket = systemRepository.monitorTicket().ticket
        // Monitor ticket 是短时（30s）一次性凭证，协议规定经 URL query 传递；
        // 长时 JWT / Agent Token 才禁止入 URL（websocket-protocol.md §Security）。
        val url = "${Constants.WS_URL}?ticket=$ticket"
        val request = Request.Builder()
            .url(url)
            .build()

        val ws = webSocketClient.newWebSocket(request, listener)
        webSocket = ws
        // 等待 onOpen 或超时（10s）
        var waited = 0
        while (waited < 10_000) {
            if (_connectionState.value == ConnectionState.CONNECTED) {
                break
            }
            delay(200)
            waited += 200
        }
        // 超时未连接：显式关闭并清空引用，避免旧连接悬挂、后续重连覆盖导致旧回调改写状态。
        if (_connectionState.value != ConnectionState.CONNECTED) {
            ws.cancel()
            if (webSocket === ws) {
                webSocket = null
            }
        }
    }

    private val listener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            _connectionState.value = ConnectionState.CONNECTED
            // 握手已消费 ticket 并认证用户，连接建立后直接订阅服务器
            subscribedServers.toList().forEach { sendSubscribe(it) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val frame = parser.parseFrame(text)
                val message = parser.parseByType(frame) ?: return
                if (message is WsMessage.Error) {
                    _connectionState.value = ConnectionState.ERROR
                }
                _messages.tryEmit(message)
            } catch (e: Exception) {
                // 解析失败（契约漂移）静默丢弃，避免拖垮连接
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private fun sendSubscribe(serverId: Long) {
        val ws = webSocket ?: return
        val frame = buildJsonObject {
            put("type", WsFrameType.METRICS_SUBSCRIBE)
            put("message_id", UUID.randomUUID().toString())
            put(
                "payload",
                buildJsonObject { put("server_id", serverId) }
            )
        }
        ws.send(frame.toString())
    }

    /**
     * 发送终端控制帧（terminal.open/input/resize/close）。
     * 帧外壳：type + UUID message_id + UTC timestamp + payload。
     * 仅当连接已建立（CONNECTED）时发送，否则静默丢弃（由调用方判断连接状态）。
     *
     * @return 是否发送成功（连接是否可用）
     */
    fun sendTerminalFrame(type: String, payload: kotlinx.serialization.json.JsonObject): Boolean {
        val ws = webSocket ?: return false
        val frame = buildJsonObject {
            put("type", type)
            put("message_id", UUID.randomUUID().toString())
            put("timestamp", java.time.Instant.now().toString())
            put("payload", payload)
        }
        return ws.send(frame.toString())
    }

    private fun sendFrame(jsonObject: kotlinx.serialization.json.JsonObject) {
        webSocket?.send(jsonObject.toString())
    }
}

/** WebSocket 连接状态。 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR,
}
