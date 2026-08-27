package com.susumonitor.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.susumonitor.data.model.SshAuthTypeValues

/**
 * 服务器表单页：新建/编辑共用（SSH 凭据）。
 * @param serverId >0 为编辑模式
 * @param onBack 返回
 * @param onSaved 保存成功回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerFormScreen(
    serverId: Long,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: ServerFormViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onSaved()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.isEditMode) "编辑服务器" else "新建服务器") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) { Text("←") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FormField(
                value = uiState.name,
                onChange = viewModel::onNameChange,
                label = "名称",
                error = uiState.fieldErrors["name"],
            )
            FormField(
                value = uiState.host,
                onChange = viewModel::onHostChange,
                label = "主机",
                error = uiState.fieldErrors["host"],
            )
            FormField(
                value = uiState.description,
                onChange = viewModel::onDescriptionChange,
                label = "描述",
                multiline = true,
            )
            HorizontalLabelDivider("SSH 连接")
            FormField(
                value = uiState.sshHost,
                onChange = viewModel::onSshHostChange,
                label = "SSH 主机",
                error = uiState.fieldErrors["sshHost"],
            )
            FormField(
                value = uiState.sshPort,
                onChange = viewModel::onSshPortChange,
                label = "SSH 端口",
                error = uiState.fieldErrors["sshPort"],
            )
            FormField(
                value = uiState.sshUser,
                onChange = viewModel::onSshUserChange,
                label = "SSH 用户",
                error = uiState.fieldErrors["sshUser"],
            )

            // 认证方式
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("认证方式", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.weight(1f))
                RadioButton(
                    selected = uiState.sshAuthType == SshAuthTypeValues.PASSWORD,
                    onClick = { viewModel.onAuthTypeChange(SshAuthTypeValues.PASSWORD) },
                )
                Text("密码")
                RadioButton(
                    selected = uiState.sshAuthType == SshAuthTypeValues.PRIVATE_KEY,
                    onClick = { viewModel.onAuthTypeChange(SshAuthTypeValues.PRIVATE_KEY) },
                )
                Text("私钥")
            }

            if (uiState.sshAuthType == SshAuthTypeValues.PASSWORD) {
                FormField(
                    value = uiState.sshPassword,
                    onChange = viewModel::onSshPasswordChange,
                    label = if (uiState.isEditMode) "SSH 密码（留空保留原值）" else "SSH 密码",
                    isPassword = true,
                    error = uiState.fieldErrors["sshPassword"],
                )
            } else {
                FormField(
                    value = uiState.sshPrivateKey,
                    onChange = viewModel::onSshPrivateKeyChange,
                    label = if (uiState.isEditMode) "私钥（留空保留原值）" else "私钥",
                    multiline = true,
                    error = uiState.fieldErrors["sshPrivateKey"],
                )
                FormField(
                    value = uiState.sshPrivateKeyPassphrase,
                    onChange = viewModel::onSshPrivateKeyPassphraseChange,
                    label = "私钥口令（可选）",
                    isPassword = true,
                )
            }

            if (uiState.errorMessage != null) {
                Text(
                    text = uiState.errorMessage.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = viewModel::submit,
                    enabled = !uiState.isLoading,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (uiState.isLoading) "保存中…" else "保存")
                }
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text("取消")
                }
            }
        }
    }
}

@Composable
private fun FormField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    error: String? = null,
    multiline: Boolean = false,
    isPassword: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = if (isPassword) {
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun HorizontalLabelDivider(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}
