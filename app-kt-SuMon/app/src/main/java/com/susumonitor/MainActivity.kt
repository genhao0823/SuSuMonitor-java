package com.susumonitor

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.susumonitor.service.MonitorForegroundService
import com.susumonitor.service.NotificationHelper
import com.susumonitor.ui.MainViewModel
import com.susumonitor.ui.login.LoginScreen
import com.susumonitor.ui.navigation.AppNavHost
import com.susumonitor.ui.theme.SuSuMonitorTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 主 Activity：会话路由（未登录→登录页，已登录→主导航）。
 * 告警通知点击经 ACTION_OPEN_ALERTS 进入，AppNavHost 默认落仪表盘；
 * MVP 阶段简化：通知点击唤起 App（已在 MainActivity 顶部），跳转告警页留待后续。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SuSuMonitorTheme {
                val session by mainViewModel.session.collectAsStateWithLifecycle()
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (session == null) {
                        // 未登录 / 会话未就绪：登录页
                        LoginScreen(onLoginSuccess = {})
                    } else {
                        AppNavHost(
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

    /** 告警通知点击：保证 App 唤起并落在登录/主页（由会话路由自动处理）。 */
    private fun handleNotificationIntent(intent: Intent?) {
        if (intent?.action == NotificationHelper.ACTION_OPEN_ALERTS) {
            // MVP 阶段：App 已唤起即满足通知诉求；告警 Tab 跳转留待后续迭代
        }
    }
}
