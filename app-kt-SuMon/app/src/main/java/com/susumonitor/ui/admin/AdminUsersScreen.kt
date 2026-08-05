package com.susumonitor.ui.admin

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.susumonitor.data.model.AdminUserVo
import com.susumonitor.ui.components.EmptyState
import com.susumonitor.ui.components.ErrorState
import com.susumonitor.ui.components.LoadingState
import com.susumonitor.util.TimeFormatter

/**
 * 用户审核页（admin）：状态 Tab + 搜索 + 分页 + 单条/批量通过拒绝。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminUsersScreen(
    onBack: () -> Unit,
    viewModel: AdminUsersViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableStateOf(0) }
    var showBatchRejectDialog by remember { mutableStateOf(false) }
    var rejectTarget by remember { mutableStateOf<AdminUserVo?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("用户审核") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) { Text("←") }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            TabRow(selectedTabIndex = selectedTab) {
                ReviewTab.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = {
                            selectedTab = index
                            viewModel.selectTab(tab)
                        },
                        text = { Text(tab.label) },
                    )
                }
            }

            // 搜索 + 批量操作
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = uiState.keyword,
                    onValueChange = viewModel::onKeywordChange,
                    label = { Text("搜索用户名") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                if (uiState.tab == ReviewTab.PENDING && uiState.selectedIds.isNotEmpty()) {
                    OutlinedButton(onClick = { viewModel.batchApprove(uiState.selectedIds.toList()) }) {
                        Text("通过(${uiState.selectedIds.size})")
                    }
                    OutlinedButton(onClick = { showBatchRejectDialog = true }) {
                        Text("拒绝(${uiState.selectedIds.size})")
                    }
                }
            }

            when {
                uiState.isLoading && uiState.users.isEmpty() -> LoadingState()
                uiState.errorMessage != null && uiState.users.isEmpty() ->
                    ErrorState(
                        message = uiState.errorMessage.orEmpty(),
                        onRetry = { viewModel.loadFirstPage() },
                    )
                uiState.users.isEmpty() -> EmptyState("暂无用户")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(uiState.users, key = { it.id }) { user ->
                        AdminUserRow(
                            user = user,
                            selected = user.id in uiState.selectedIds,
                            selectable = uiState.tab == ReviewTab.PENDING,
                            onSelect = { viewModel.toggleSelect(user.id) },
                            onApprove = { viewModel.approveUser(user.id) },
                            onReject = { rejectTarget = user },
                        )
                    }
                }
            }

            // 批量操作失败明细
            uiState.batchFailed?.let { result ->
                AlertDialog(
                    onDismissRequest = viewModel::clearError,
                    title = { Text("批量操作结果") },
                    text = {
                        Text(
                            text = "成功 ${result.processed}，失败 ${result.failed}。\n失败用户 ID：${result.failedIds.joinToString(", ")}",
                        )
                    },
                    confirmButton = { TextButton(onClick = viewModel::clearError) { Text("确定") } },
                )
            }

            // 批量拒绝确认
            if (showBatchRejectDialog) {
                AlertDialog(
                    onDismissRequest = { showBatchRejectDialog = false },
                    title = { Text("批量拒绝") },
                    text = { Text("确定拒绝选中的 ${uiState.selectedIds.size} 个用户？") },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.batchReject(uiState.selectedIds.toList())
                            showBatchRejectDialog = false
                        }) { Text("拒绝") }
                    },
                    dismissButton = { TextButton(onClick = { showBatchRejectDialog = false }) { Text("取消") } },
                )
            }

            // 单条拒绝确认
            rejectTarget?.let { user ->
                AlertDialog(
                    onDismissRequest = { rejectTarget = null },
                    title = { Text("拒绝用户") },
                    text = { Text("确定拒绝「${user.username}」？") },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.rejectUser(user.id)
                            rejectTarget = null
                        }) { Text("拒绝") }
                    },
                    dismissButton = { TextButton(onClick = { rejectTarget = null }) { Text("取消") } },
                )
            }
        }
    }
}

@Composable
private fun AdminUserRow(
    user: AdminUserVo,
    selected: Boolean,
    selectable: Boolean,
    onSelect: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectable) {
            Checkbox(checked = selected, onCheckedChange = { onSelect() })
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = user.username, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "ID ${user.id} · 注册 ${TimeFormatter.formatLocal(user.createdAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (user.reviewStatus == "pending") {
            Button(onClick = onApprove) { Text("通过") }
            TextButton(onClick = onReject) { Text("拒绝", color = MaterialTheme.colorScheme.error) }
        }
    }
}
