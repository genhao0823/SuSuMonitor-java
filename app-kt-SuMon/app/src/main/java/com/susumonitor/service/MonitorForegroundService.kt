package com.susumonitor.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.susumonitor.api.WsClient
import com.susumonitor.data.WsMessage
import com.susumonitor.data.model.AlertPushPayload
import com.susumonitor.data.model.MetricsUpdatePayload
import com.susumonitor.data.model.ServerStatusPushPayload
import com.susumonitor.util.ValueFormatter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 前台监控服务：常驻 START_STICKY，持有 [WsClient] 维持实时连接，
 * 收到 `alert.push` 时发布高优通知，并把实时数据转发给 UI 层。
 *
 * 通信模型：本服务为进程内单例，通过静态 [eventFlow] / [metricsFlow] 暴露，
 * UI 层（ViewModel）收集即可；不做跨进程 AIDL（MVP 边界）。
 */
@AndroidEntryPoint
class MonitorForegroundService : Service() {

    companion object {
        private const val TAG = "MonitorService"
        const val ACTION_START = "com.susumonitor.action.START_MONITOR"
        const val ACTION_STOP = "com.susumonitor.action.STOP_MONITOR"

        /** UI 层可订阅的告警事件流。 */
        private val _alertEvents = MutableSharedFlow<AlertPushPayload>(extraBufferCapacity = 16)
        val alertEvents = _alertEvents.asSharedFlow()

        /** UI 层可订阅的实时指标流。 */
        private val _metricsUpdates = MutableSharedFlow<MetricsUpdatePayload>(extraBufferCapacity = 64)
        val metricsUpdates = _metricsUpdates.asSharedFlow()

        /** UI 层可订阅的服务器状态转换流。 */
        private val _statusUpdates = MutableSharedFlow<ServerStatusPushPayload>(extraBufferCapacity = 16)
        val statusUpdates = _statusUpdates.asSharedFlow()

        /** 当前订阅的服务器 ID 集合（服务进程内共享）。 */
        private val _subscribedServerIds = MutableStateFlow<List<Long>>(emptyList())
        val subscribedServerIds = _subscribedServerIds.asStateFlow()

        @Volatile
        private var runningInstance: MonitorForegroundService? = null

        /** 服务是否运行中（供 UI 判断）。 */
        val isRunning: Boolean get() = runningInstance != null

        /**
         * 启动监控服务并更新订阅列表。
         * @param context 应用上下文
         * @param serverIds 要订阅的服务器 ID 列表
         */
        fun start(context: Context, serverIds: List<Long>) {
            _subscribedServerIds.value = serverIds
            val intent = Intent(context, MonitorForegroundService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        /** 停止监控服务。 */
        fun stop(context: Context) {
            val intent = Intent(context, MonitorForegroundService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    @Inject
    lateinit var wsClient: WsClient

    @Inject
    lateinit var notificationHelper: NotificationHelper

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        runningInstance = this
        notificationHelper.createChannels()
        startForegroundWithNotification()
        collectWsMessages()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // ACTION_START 或空 intent（系统重启恢复）：确保已订阅列表被应用
                startForegroundWithNotification()
                wsClient.start(_subscribedServerIds.value)
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        runningInstance = null
        collectJob?.cancel()
        serviceScope.cancel()
        wsClient.stop()
        notificationHelper.cancelForegroundNotification()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 前台通知（含渠道初始化），兼容 Android 8+ 通知渠道与 14+ 前台服务类型。 */
    private fun startForegroundWithNotification() {
        val serverCount = _subscribedServerIds.value.size
        val notification = notificationHelper.buildForegroundNotification(serverCount)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID_FOREGROUND,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID_FOREGROUND, notification)
        }
    }

    /** 收集 WsClient 解析后的消息：告警→通知+事件流，指标/状态→事件流。 */
    private fun collectWsMessages() {
        collectJob = serviceScope.launch {
            wsClient.messages.collect { message ->
                when (message) {
                    is WsMessage.AlertPush -> {
                        _alertEvents.tryEmit(message.payload)
                        postAlertNotification(message.payload)
                    }
                    is WsMessage.MetricsUpdate -> _metricsUpdates.tryEmit(message.payload)
                    is WsMessage.ServerStatusUpdate -> _statusUpdates.tryEmit(message.payload)
                    is WsMessage.Error -> Log.w(TAG, "ws error code=${message.payload.code} msg=${message.payload.message}")
                    // 终端帧由 TerminalClient 独立收集，前台服务不处理
                    is WsMessage.TerminalOpened,
                    is WsMessage.TerminalOutput,
                    is WsMessage.TerminalClosed,
                    is WsMessage.TerminalError -> Unit
                }
            }
        }
    }

    /** 告警到达：发布高优通知。 */
    private fun postAlertNotification(payload: AlertPushPayload) {
        val alert = payload.alert
        notificationHelper.showAlertNotification(
            serverName = "服务器 #${payload.serverId}",
            metricLabel = ValueFormatter.metricLabel(alert.metric),
            currentValue = ValueFormatter.formatMetric(alert.metric, alert.currentValue),
            thresholdValue = ValueFormatter.formatMetric(alert.metric, alert.thresholdValue),
            level = alert.level,
        )
    }
}
