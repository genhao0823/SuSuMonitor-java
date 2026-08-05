package com.susumonitor.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.susumonitor.data.model.Server
import com.susumonitor.ui.components.EmptyState
import com.susumonitor.ui.components.ErrorState
import com.susumonitor.ui.components.LoadingState
import com.susumonitor.ui.components.ServerStatusCard
import kotlinx.coroutines.delay

/**
 * 服务器列表页：搜索防抖、排序、分页、下拉刷新、SSH 测试、删除（admin）。
 * @param onServerClick 点击 → 详情
 * @param onCreateServer 新建服务器（admin）
 * @param onEditServer 编辑服务器（admin）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    onServerClick: (Long) -> Unit,
    onCreateServer: () -> Unit,
    onEditServer: (Long) -> Unit,
    viewModel: ServerListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 搜索防抖：本地编辑态，500ms 停顿后触发加载
    var keywordInput by remember { mutableStateOf(uiState.keyword) }
    LaunchedEffect(keywordInput) {
        delay(500)
        viewModel.applyKeyword(keywordInput)
    }

    var deleteTarget by remember { mutableStateOf<Server?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("服务器") }) },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // 搜索栏 + 排序
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = keywordInput,
                    onValueChange = { keywordInput = it },
                    label = { Text("搜索名称/主机") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                if (uiState.isAdmin) {
                    Button(onClick = onCreateServer) { Text("新建") }
                }
            }
            HorizontalDivider()

            when {
                uiState.isLoading && uiState.servers.isEmpty() -> LoadingState()
                uiState.errorMessage != null && uiState.servers.isEmpty() ->
                    ErrorState(
                        message = uiState.errorMessage.orEmpty(),
                        onRetry = { viewModel.loadFirstPage() },
                    )
                uiState.servers.isEmpty() -> EmptyState(text = "暂无服务器")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(uiState.servers, key = { it.id }) { server ->
                        ServerListCard(
                            server = server,
                            latest = uiState.latestMetrics[server.id],
                            isAdmin = uiState.isAdmin,
                            sshTest = uiState.sshTestResults[server.id],
                            onTestSsh = { viewModel.testSsh(server.id) },
                            onDelete = { deleteTarget = server },
                            onClick = { onServerClick(server.id) },
                            onEdit = { onEditServer(server.id) },
                        )
                    }
                    // 加载更多：滚到底触发
                    if (uiState.hasMore) {
                        item {
                            LaunchedEffect(uiState.page) {
                                viewModel.loadMore()
                            }
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除服务器") },
            text = { Text("确定删除「${deleteTarget?.name}」？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget?.let { viewModel.deleteServer(it.id) }
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

/** 服务器列表卡片（含操作按钮）。 */
@Composable
private fun ServerListCard(
    server: Server,
    latest: com.susumonitor.data.model.Metrics?,
    isAdmin: Boolean,
    sshTest: com.susumonitor.data.model.SshTestResult?,
    onTestSsh: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onClick: () -> Unit,
) {
    Column {
        ServerStatusCard(
            serverName = server.name,
            host = server.host,
            status = server.status,
            agentStatus = server.agentStatus,
            lastHeartbeatAt = server.lastHeartbeatAt,
            latest = latest,
            onClick = onClick,
        )
        // 操作栏（admin 可见 SSH 测试/编辑/删除）
        if (isAdmin) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onTestSsh, modifier = Modifier.weight(1f)) {
                    Text(if (sshTest != null) "SSH ✓ ${sshTest.durationMs}ms" else "SSH 测试")
                }
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Text("编辑")
                }
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                    Text("删除")
                }
            }
        }
    }
}
