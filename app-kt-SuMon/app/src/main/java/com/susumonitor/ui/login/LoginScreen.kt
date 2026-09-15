package com.susumonitor.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.susumonitor.ui.theme.SusuPurple
import com.susumonitor.ui.theme.SusuPink

/**
 * 登录/注册页。
 * @param onLoginSuccess 登录成功后回调（导航层跳转主页）
 */
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var isRegister by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    // 一次性初始化令牌（仅首管理员未初始化时显示与必填，批次 8）
    var bootstrapToken by rememberSaveable { mutableStateOf("") }
    // 本地表单校验错误（如两次密码不一致），与服务端错误分开展示
    var localErrorMessage by remember { mutableStateOf<String?>(null) }

    // 进入页面时查询首管理员初始化状态，决定注册表单是否展示令牌输入框
    LaunchedEffect(Unit) {
        viewModel.refreshBootstrapStatus()
    }

    // 登录成功后跳转
    LaunchedEffect(uiState.loggedInUser) {
        if (uiState.loggedInUser != null) {
            onLoginSuccess()
        }
    }

    Scaffold { innerPadding ->
        // 外层 Box 居中 + 内层可滚动：避免 verticalScroll 内直接 Arrangement.Center
        // （该组合在滚动容器内会干扰输入框焦点/键盘交互，导致点击后 IME 异常）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 苏苏主题 Logo 占位
                Surface(
                    modifier = Modifier.size(72.dp).clip(CircleShape),
                    color = SusuPink,
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "苏",
                            color = SusuPurple,
                            style = MaterialTheme.typography.headlineMedium,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "SuSuMonitor", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "涂山苏苏 · 服务器监控",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(32.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    // 显式指定普通文本键盘类型，避免 GBoard 对未指定类型的差异行为
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isRegister) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = { Text("确认密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // 系统仍待初始化首管理员时，注册必须携带服务器启动日志中的一次性令牌（批次 8）
                if (isRegister && uiState.bootstrapPending) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = bootstrapToken,
                        onValueChange = { bootstrapToken = it },
                        label = { Text("初始化令牌") },
                        placeholder = { Text("见服务器启动日志的一次性令牌") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "系统尚未初始化管理员：请从服务器启动日志（docker logs / journalctl）" +
                            "获取一次性初始化令牌，注册成功后令牌立即失效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (uiState.errorMessage != null || localErrorMessage != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = localErrorMessage ?: uiState.errorMessage.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = {
                        if (isRegister) {
                            if (password != confirmPassword) {
                                localErrorMessage = "两次输入的密码不一致"
                                return@Button
                            }
                            // 待初始化时令牌必填（批次 8），长度与后端签发值 32-128 一致
                            if (uiState.bootstrapPending &&
                                (bootstrapToken.trim().length < 32 || bootstrapToken.trim().length > 128)
                            ) {
                                localErrorMessage = "请输入服务器启动日志中的初始化令牌（32-128 位）"
                                return@Button
                            }
                            localErrorMessage = null
                            viewModel.register(username, password, bootstrapToken.trim()) {
                                username = ""
                                password = ""
                                bootstrapToken = ""
                                isRegister = false
                            }
                        } else {
                            localErrorMessage = null
                            viewModel.login(username, password)
                        }
                    },
                    enabled = username.isNotBlank() && password.isNotBlank() &&
                        (!isRegister || !uiState.bootstrapPending || bootstrapToken.isNotBlank()) &&
                        !uiState.isLoading,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(if (isRegister) "注册" else "登录")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = { isRegister = !isRegister }) {
                    Text(if (isRegister) "已有账号？去登录" else "没有账号？去注册")
                }
            }
        }
    }
}
