package com.susumonitor.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.susumonitor.ui.ai.AiAlertExplanationScreen
import com.susumonitor.ui.ai.AiDiagnosisScreen
import com.susumonitor.ui.ai.AiQaScreen
import com.susumonitor.ui.ai.AutoApprovalPolicyScreen
import com.susumonitor.ui.ai.CommandCreateScreen
import com.susumonitor.ui.ai.CommandRunDetailScreen
import com.susumonitor.ui.ai.CommandRunsScreen
import com.susumonitor.ui.admin.AdminUsersScreen
import com.susumonitor.ui.alerts.AlertListScreen
import com.susumonitor.ui.alerts.AlertNotificationsScreen
import com.susumonitor.ui.alerts.AlertRulesScreen
import com.susumonitor.ui.dashboard.DashboardScreen
import com.susumonitor.ui.servers.ServerDetailScreen
import com.susumonitor.ui.servers.ServerFormScreen
import com.susumonitor.ui.servers.ServerListScreen
import com.susumonitor.ui.servers.ServerMetricsScreen
import com.susumonitor.ui.system.SystemMonitorScreen
import com.susumonitor.ui.terminal.TerminalScreen
import kotlinx.coroutines.flow.Flow

/** 底部导航目的地。adminOnly 的 Tab 仅对管理员显示。 */
enum class BottomTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val adminOnly: Boolean = false,
) {
    DASHBOARD("dashboard", "仪表盘", Icons.Filled.Dashboard),
    SERVERS("servers", "服务器", Icons.Filled.Dashboard),
    ALERTS("alerts", "告警", Icons.Filled.Notifications),
    AI("aiQa", "AI", Icons.Filled.Psychology, adminOnly = true),
    SETTINGS("settings", "设置", Icons.Filled.Settings),
}

/**
 * 主导航：底部 Tab + 全功能路由。
 * - 底部 Tab：仪表盘 / 服务器 / 告警 / AI（admin）/ 设置
 * - 详情/表单/监控/规则/审核/命令为堆栈页（无底部栏）
 */
@Composable
fun AppNavHost(
    isAdmin: Boolean,
    isApproved: Boolean,
    openAlertsRequest: Flow<Unit>,
    onLogout: () -> Unit,
) {
    val navController = rememberNavController()

    /** 切到指定底部 Tab（与底部栏点击一致的导航配置）。 */
    fun navigateToTab(tab: BottomTab) {
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    // 告警通知点击深链：切到告警 Tab
    LaunchedEffect(openAlertsRequest) {
        openAlertsRequest.collect { navigateToTab(BottomTab.ALERTS) }
    }

    Scaffold(
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination
            val stackRoutes = setOf(
                "serverDetail/{serverId}",
                "serverForm/{serverId}",
                "serverMetrics/{serverId}",
                "serverTerminal/{serverId}",
                "alertRules",
                "adminUsers",
                "aiDiagnosis/{serverId}",
                "commandRuns",
                "commandRunDetail/{runId}",
                "commandCreate?serverId={serverId}",
                "commandPolicy",
                "aiAlertExplanation/{recordId}",
                "alertNotifications/{recordId}",
                "systemMonitor",
            )
            val showBottomBar = stackRoutes.none { route ->
                currentDestination?.hierarchy?.any { it.route == route } == true
            }
            if (showBottomBar) {
                NavigationBar {
                    BottomTab.entries
                        .filter { !it.adminOnly || isAdmin }
                        .forEach { tab ->
                            val selected = currentDestination?.hierarchy?.any {
                                it.route == tab.route
                            } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateToTab(tab) },
                                icon = {
                                    Icon(
                                        tab.icon,
                                        contentDescription = tab.label,
                                        tint = if (selected) Color(0xFF9C7BD8) else Color.Gray,
                                    )
                                },
                                label = { Text(tab.label) },
                            )
                        }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = BottomTab.DASHBOARD.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(BottomTab.DASHBOARD.route) {
                DashboardScreen(
                    isAdmin = isAdmin,
                    onServerClick = { serverId ->
                        navController.navigate("serverDetail/$serverId")
                    },
                    onViewAlerts = { navController.navigate(BottomTab.ALERTS.route) },
                    onViewAdmin = { navController.navigate("adminUsers") },
                    onViewSystemMonitor = { navController.navigate("systemMonitor") },
                )
            }
            composable(BottomTab.SERVERS.route) {
                ServerListScreen(
                    onServerClick = { serverId ->
                        navController.navigate("serverDetail/$serverId")
                    },
                    onCreateServer = {
                        navController.navigate("serverForm/0")
                    },
                    onEditServer = { serverId ->
                        navController.navigate("serverForm/$serverId")
                    },
                )
            }
            composable(BottomTab.ALERTS.route) {
                AlertListScreen(
                    onViewRules = { navController.navigate("alertRules") },
                    isAdmin = isAdmin,
                    onOpenExplanation = { recordId ->
                        navController.navigate("aiAlertExplanation/$recordId")
                    },
                    onOpenNotifications = { recordId ->
                        navController.navigate("alertNotifications/$recordId")
                    },
                )
            }
            composable(BottomTab.AI.route) {
                AiQaScreen(
                    onOpenCommands = { navController.navigate("commandRuns") },
                )
            }
            composable(BottomTab.SETTINGS.route) {
                SettingsScreen(onLogout = onLogout)
            }
            composable(
                route = "serverDetail/{serverId}",
                arguments = listOf(navArgument("serverId") { type = NavType.LongType }),
            ) { backStackEntry ->
                val serverId = backStackEntry.arguments?.getLong("serverId") ?: 0L
                ServerDetailScreen(
                    serverId = serverId,
                    isAdmin = isAdmin,
                    isApproved = isApproved,
                    onBack = { navController.popBackStack() },
                    onEdit = {
                        navController.navigate("serverForm/$serverId")
                    },
                    onMetrics = {
                        navController.navigate("serverMetrics/$serverId")
                    },
                    onTerminal = {
                        navController.navigate("serverTerminal/$serverId")
                    },
                    onAiDiagnosis = {
                        navController.navigate("aiDiagnosis/$serverId")
                    },
                    onCreateCommand = {
                        navController.navigate("commandCreate?serverId=$serverId")
                    },
                )
            }
            composable(
                route = "serverTerminal/{serverId}",
                arguments = listOf(navArgument("serverId") { type = NavType.LongType }),
            ) { backStackEntry ->
                val serverId = backStackEntry.arguments?.getLong("serverId") ?: 0L
                TerminalScreen(
                    serverId = serverId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "serverForm/{serverId}",
                arguments = listOf(navArgument("serverId") { type = NavType.LongType }),
            ) { backStackEntry ->
                val serverId = backStackEntry.arguments?.getLong("serverId") ?: 0L
                ServerFormScreen(
                    serverId = serverId,
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                )
            }
            composable(
                route = "serverMetrics/{serverId}",
                arguments = listOf(navArgument("serverId") { type = NavType.LongType }),
            ) { backStackEntry ->
                val serverId = backStackEntry.arguments?.getLong("serverId") ?: 0L
                ServerMetricsScreen(
                    serverId = serverId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "aiDiagnosis/{serverId}",
                arguments = listOf(navArgument("serverId") { type = NavType.LongType }),
            ) { backStackEntry ->
                val serverId = backStackEntry.arguments?.getLong("serverId") ?: 0L
                AiDiagnosisScreen(
                    serverId = serverId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable("commandRuns") {
                CommandRunsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDetail = { runId ->
                        navController.navigate("commandRunDetail/$runId")
                    },
                    onCreate = {
                        navController.navigate("commandCreate?serverId=0")
                    },
                    onOpenPolicy = {
                        navController.navigate("commandPolicy")
                    },
                )
            }
            composable("commandPolicy") {
                AutoApprovalPolicyScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "commandRunDetail/{runId}",
                arguments = listOf(navArgument("runId") { type = NavType.LongType }),
            ) { backStackEntry ->
                val runId = backStackEntry.arguments?.getLong("runId") ?: 0L
                CommandRunDetailScreen(
                    runId = runId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "commandCreate?serverId={serverId}",
                arguments = listOf(
                    navArgument("serverId") {
                        type = NavType.LongType
                        defaultValue = 0L
                    },
                ),
            ) { backStackEntry ->
                val serverId = backStackEntry.arguments?.getLong("serverId") ?: 0L
                CommandCreateScreen(
                    serverId = serverId,
                    onBack = { navController.popBackStack() },
                    onOpenDetail = { runId ->
                        navController.navigate("commandRunDetail/$runId") {
                            // 发起页弹出，避免批准后返回又回到表单
                            popUpTo("commandCreate?serverId={serverId}") { inclusive = true }
                        }
                    },
                )
            }
            composable(
                route = "aiAlertExplanation/{recordId}",
                arguments = listOf(navArgument("recordId") { type = NavType.LongType }),
            ) { backStackEntry ->
                val recordId = backStackEntry.arguments?.getLong("recordId") ?: 0L
                AiAlertExplanationScreen(
                    recordId = recordId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "alertNotifications/{recordId}",
                arguments = listOf(navArgument("recordId") { type = NavType.LongType }),
            ) { backStackEntry ->
                val recordId = backStackEntry.arguments?.getLong("recordId") ?: 0L
                AlertNotificationsScreen(
                    recordId = recordId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable("systemMonitor") {
                SystemMonitorScreen(onBack = { navController.popBackStack() })
            }
            composable("alertRules") {
                AlertRulesScreen(onBack = { navController.popBackStack() })
            }
            composable("adminUsers") {
                AdminUsersScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
