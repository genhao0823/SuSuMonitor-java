package com.susumonitor.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.susumonitor.ui.alerts.AlertListScreen
import com.susumonitor.ui.dashboard.DashboardScreen
import com.susumonitor.ui.servers.ServerDetailScreen
import com.susumonitor.ui.servers.ServerListScreen

/** 底部导航目的地。 */
enum class BottomTab(val route: String, val label: String, val icon: ImageVector) {
    DASHBOARD("dashboard", "仪表盘", Icons.Filled.Dashboard),
    SERVERS("servers", "服务器", Icons.Filled.Dashboard),
    ALERTS("alerts", "告警", Icons.Filled.Notifications),
    SETTINGS("settings", "设置", Icons.Filled.Settings),
}

/**
 * 主导航：底部 Tab（仪表盘/服务器/告警/设置）+ 详情页（serverDetail）。
 */
@Composable
fun AppNavHost(
    onLogout: () -> Unit,
) {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination
            // 详情页不显示底部栏
            val showBottomBar = currentDestination?.route != "serverDetail"
            if (showBottomBar) {
                NavigationBar {
                    BottomTab.entries.forEach { tab ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == tab.route
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
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
                    onServerClick = { serverId ->
                        navController.navigate("serverDetail/$serverId")
                    },
                )
            }
            composable(BottomTab.SERVERS.route) {
                ServerListScreen(
                    onServerClick = { serverId ->
                        navController.navigate("serverDetail/$serverId")
                    },
                )
            }
            composable(BottomTab.ALERTS.route) {
                AlertListScreen()
            }
            composable(BottomTab.SETTINGS.route) {
                SettingsScreen(onLogout = onLogout)
            }
            composable("serverDetail/{serverId}") { backStackEntry ->
                val serverId = backStackEntry.arguments?.getString("serverId")?.toLongOrNull() ?: 0L
                ServerDetailScreen(
                    serverId = serverId,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
