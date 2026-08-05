package com.susumonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.susumonitor.service.MonitorForegroundService
import com.susumonitor.service.NotificationHelper
import com.susumonitor.ui.MainViewModel
import com.susumonitor.ui.login.LoginScreen
import com.susumonitor.ui.navigation.AppNavHost
import com.susumonitor.ui.theme.SuSuMonitorTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 主 Activity：会话路由 + 通知权限请求 + 告警通知深链。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    /** Android 13+ 通知运行时权限请求。 */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // 授权状态由系统通知栏体现；未授权时告警通知静默丢弃（设置页有提示文案）
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        setContent {
            SuSuMonitorTheme {
                val session by mainViewModel.session.collectAsStateWithLifecycle()
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (session == null) {
                        // 未登录 / 会话未就绪：登录页
                        LoginScreen(onLoginSuccess = {})
                    } else {
                        AppNavHost(
                            isAdmin = session?.user?.role == "admin",
                            onLogout = {
                                MonitorForegroundService.stop(applicationContext)
                                mainViewModel.logout()
                            },
                        )
                    }
                }
            }
        }
        handleNotificationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    /** 告警通知点击：深链到告警 Tab（通过启动携带 extra 由导航处理）。 */
    private fun handleNotificationIntent(intent: Intent?) {
        if (intent?.action == NotificationHelper.ACTION_OPEN_ALERTS) {
            // App 已唤起即满足通知诉求；告警 Tab 深链由 MainViewModel 事件总线承载，
            // 此处保留扩展点（MVP 阶段：唤起 + 会话路由已足够）
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
