<template>
  <div class="terminal-view">
    <PageHeader
      title="Web 终端"
      :subtitle="`目标服务器 #${serverId} 的 root PTY 会话(MVP-7 T4 最小可用版本)`"
    >
      <template #actions>
        <el-tag
          :type="phaseTagType"
          effect="dark"
          class="terminal-view__phase"
        >
          {{ phaseLabel }}
        </el-tag>
        <el-button
          v-if="phase === 'open'"
          size="small"
          type="danger"
          plain
          @click="handleDisconnect"
        >
          断开
        </el-button>
        <el-button
          v-else-if="canRetry"
          size="small"
          @click="retry"
        >
          重试
        </el-button>
        <el-button
          v-else-if="phase === 'closed'"
          size="small"
          @click="retry"
        >
          重连
        </el-button>
      </template>
    </PageHeader>

    <el-alert
      v-if="bannerError"
      :title="bannerError"
      type="error"
      show-icon
      :closable="false"
      class="terminal-view__alert"
    />

    <el-card
      shadow="never"
      class="terminal-view__card"
    >
      <div
        ref="termHost"
        class="terminal-view__host"
      />
      <div
        v-if="!connected"
        class="terminal-view__placeholder"
      >
        <el-icon class="terminal-view__placeholder-icon">
          <Loading />
        </el-icon>
        <span>{{ placeholderText }}</span>
      </div>
    </el-card>

    <div class="terminal-view__hints">
      <el-text
        size="small"
        type="info"
      >
        终端输入会通过 Base64(UTF-8)经 /ws/monitor 复用通道转发至 Agent PTY。
        仅 <code>approved</code> 用户可开启 root 会话。
        关闭页面或路由切换会自动发 terminal.close,断开时不会自动重连。
      </el-text>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * Web 终端视图(MVP-7 T4)。
 *
 * 设计要点:
 * - 复用 MonitorWebSocket 现有 `/ws/monitor` channel,不另开 WebSocket
 *   (避免双 ticket + 协议层 message_id 冲突)
 * - TerminalWebSocket 仅在 MonitorWebSocket.onSocketReady 拿到 socket 后才创建,
 *   避免与 metrics.subscribe 形成竞态
 * - 不做自动重连:WebSocket 断开后 socket.close 兜底,用户需手动 retry
 * - xterm 资源在 onBeforeUnmount 显式 dispose 防止泄漏
 */

import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { useAuthStore } from '@/stores/auth'
import { useMetricsStore } from '@/stores/metrics'
import { MonitorWebSocket } from '@/services/websocket'
import { TerminalWebSocket, type TerminalPhase } from '@/services/terminal-ws'
import { TerminalErrorCode } from '@/types/terminal'
import { ErrorCode } from '@/types/error-code'
import PageHeader from '@/components/PageHeader.vue'

const route = useRoute()
const auth = useAuthStore()
const metrics = useMetricsStore()

/** 路由形参 :serverId 已在 router/index.ts 用 (\d+) 约束,parse 不会 NaN */
const serverId = computed<number>(() => {
  const raw = route.params.serverId
  const value = Array.isArray(raw) ? raw[0] : raw
  return Number.parseInt(value ?? '0', 10)
})

const termHost = ref<HTMLDivElement | null>(null)
/** shallowRef 避免 Vue 把 xterm 内部状态做成 Proxy */
const term = shallowRef<Terminal | null>(null)
const fitAddon = shallowRef<FitAddon | null>(null)

const phase = ref<TerminalPhase>('idle')
const sessionId = ref<string | null>(null)
const connected = ref(false)
const bannerError = ref<string | null>(null)

let monitorWs: MonitorWebSocket | null = null
let terminalWs: TerminalWebSocket | null = null
let termResizeObserver: ResizeObserver | null = null

/** 把错误码映射成中文,优先 Terminal 专用码,再退到通用 ErrorCode。 */
function explainTerminalError(code: number, fallback: string): string {
  switch (code) {
    case TerminalErrorCode.INVALID_PAYLOAD:
      return '终端消息格式不合法'
    case TerminalErrorCode.ACCESS_DENIED:
      return '无权限:仅审核通过用户可开启 root 终端'
    case TerminalErrorCode.SESSION_NOT_FOUND:
      return '会话不存在或已关闭'
    case TerminalErrorCode.SESSION_STATE_CONFLICT:
      return '会话状态冲突,可能已关闭'
    case TerminalErrorCode.AGENT_OFFLINE:
      return '目标 Agent 离线,请确认 Go Agent 在线'
    case TerminalErrorCode.SESSION_LIMIT_REACHED:
      return '会话数已达上限'
    case TerminalErrorCode.MESSAGE_LIMIT_REACHED:
      return '终端消息频率超限,请稍候'
    case ErrorCode.UNAUTHORIZED:
      return '需要登录'
    case ErrorCode.FORBIDDEN:
      return '无权限访问'
    default:
      return fallback || `终端错误(${code})`
  }
}

const phaseLabel = computed<string>(() => {
  switch (phase.value) {
    case 'idle': return '未开始'
    case 'connecting': return '建链中'
    case 'awaiting_open': return '等待后端确认'
    case 'open': return '已连接'
    case 'closing': return '关闭中'
    case 'closed': return '已断开'
  }
  return '未知'
})

const phaseTagType = computed<'success' | 'warning' | 'info' | 'danger'>(() => {
  if (phase.value === 'open') return 'success'
  if (phase.value === 'closed') return 'danger'
  if (phase.value === 'idle') return 'info'
  return 'warning'
})

const canRetry = computed<boolean>(() =>
  phase.value === 'closed' && bannerError.value !== null
)

const placeholderText = computed<string>(() => {
  if (bannerError.value) return '会话已断开,请点击右上角"重试"重新建立'
  return '正在建立 Web 终端会话...'
})

function mountXterm(): void {
  if (termHost.value === null) return
  const t = new Terminal({
    cursorBlink: true,
    fontSize: 13,
    fontFamily: '"Cascadia Code", Consolas, "Courier New", monospace',
    convertEol: false,
    scrollback: 5000,
    theme: {
      background: '#0b1020',
      foreground: '#e2e8f0',
      cursor: '#ff5b8a',
      selectionBackground: '#334155'
    }
  })
  const f = new FitAddon()
  t.loadAddon(f)
  t.open(termHost.value)
  f.fit()
  term.value = t
  fitAddon.value = f

  // 键盘输入转发到 terminal.input
  t.onData((data) => {
    terminalWs?.sendInput(data)
  })
  // 窗口尺寸变化时 resize PTY
  window.addEventListener('resize', handleResize)
  if (typeof ResizeObserver !== 'undefined') {
    termResizeObserver = new ResizeObserver(() => {
      handleResize()
    })
    termResizeObserver.observe(termHost.value)
  }
}

function handleResize(): void {
  if (fitAddon.value === null || term.value === null) return
  try {
    fitAddon.value.fit()
    if (term.value !== null) {
      terminalWs?.resize(term.value.cols, term.value.rows)
    }
  } catch {
    // fit 失败通常发生在宿主未挂载时,忽略
  }
}

function buildMonitorSocket(): void {
  monitorWs = new MonitorWebSocket(
    (value) => {
      metrics.applyRealtime(value)
    },
    (value) => {
      connected.value = value
    },
    undefined,
    (socket) => {
      // socket 已 OPEN 且 metrics.subscribe 即将发出,此处建 TerminalWebSocket
      if (term.value === null) return
      // 重新 fit:mountXterm 初始 fit 时容器可能尚未完成 CSS 布局,cols 会极小(如 2),
      // socket 就绪时容器已布局,重算确保 terminal.open 携带合理 cols。
      try {
        fitAddon.value?.fit()
      } catch {
        // 容器未就绪时忽略,沿用初始 cols
      }
      terminalWs = new TerminalWebSocket({
        socket,
        serverId: serverId.value,
        cols: term.value.cols,
        rows: term.value.rows,
        handlers: {
          onOpened: (sid, shell) => {
            sessionId.value = sid
            phase.value = 'open'
            bannerError.value = null
            term.value?.writeln(`\x1b[32m[connected] shell=${shell}\x1b[0m`)
          },
          onOutput: (text) => {
            term.value?.write(text)
          },
          onClosed: (reason) => {
            phase.value = 'closed'
            sessionId.value = null
            term.value?.writeln(`\r\n\x1b[33m[closed] ${reason}\x1b[0m`)
          },
          onError: (code, message) => {
            phase.value = 'closed'
            sessionId.value = null
            const text = explainTerminalError(code, message)
            bannerError.value = text
            term.value?.writeln(`\r\n\x1b[31m[error ${code}] ${text}\x1b[0m`)
            ElMessage.error(text)
          }
        }
      })
      terminalWs.open()
    },
    (frame) => {
      // 分发 terminal.* 帧给 TerminalWebSocket
      terminalWs?.handleIncoming(frame)
    }
  )
  monitorWs.connect(serverId.value)
}

function retry(): void {
  bannerError.value = null
  sessionId.value = null
  if (terminalWs !== null) {
    terminalWs = null
  }
  if (monitorWs !== null) {
    monitorWs.disconnect()
    monitorWs = null
  }
  buildMonitorSocket()
}

/**
 * 用户主动断开当前 PTY 会话。
 * - 调 terminalWs.close() 发 terminal.close 帧给 Java,Java 会转给 Agent 关闭 PTY
 * - monitorWs 不立即关,等 socket.onclose 自然触发 onConnected(false),phase 进入 closed
 *   后用户可点"重连"或"重试"重建会话
 */
function handleDisconnect(): void {
  if (terminalWs === null) return
  terminalWs.close()
  term.value?.writeln('\r\n\x1b[33m[disconnect] 正在关闭会话...\x1b[0m')
}

onMounted(async () => {
  if (serverId.value <= 0) {
    bannerError.value = '路由参数 serverId 非法'
    phase.value = 'closed'
    return
  }
  if (!auth.isApproved) {
    bannerError.value = '当前用户未通过审核,无法开启 Web 终端'
    phase.value = 'closed'
    return
  }
  await nextTick()
  mountXterm()
  phase.value = 'connecting'
  buildMonitorSocket()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  termResizeObserver?.disconnect()
  termResizeObserver = null
  if (terminalWs !== null) {
    terminalWs.close()
  }
  if (monitorWs !== null) {
    monitorWs.disconnect()
  }
  if (term.value !== null) {
    term.value.dispose()
    term.value = null
  }
  fitAddon.value = null
  terminalWs = null
  monitorWs = null
})
</script>

<style scoped>
.terminal-view {
  max-width: 1280px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.terminal-view__phase {
  font-weight: 600;
  letter-spacing: 0.5px;
}

.terminal-view__alert {
  border-radius: 8px;
}

.terminal-view__card {
  position: relative;
  border-radius: 12px;
  overflow: hidden;
  background: #0b1020;
  border: 1px solid #1e293b;
}

.terminal-view__card :deep(.el-card__body) {
  padding: 0;
  overflow: hidden;
}

.terminal-view__host {
  width: 100%;
  min-width: 0;
  height: 540px;
  padding: 8px;
  box-sizing: border-box;
  overflow: hidden;
}

.terminal-view__host :deep(.xterm) {
  width: 100%;
  height: 100%;
  overflow: hidden;
}

.terminal-view__placeholder {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: #94a3b8;
  font-size: 14px;
  background: rgba(11, 16, 32, 0.85);
  pointer-events: none;
}

.terminal-view__placeholder-icon {
  font-size: 20px;
  animation: terminal-spin 1.2s linear infinite;
}

@keyframes terminal-spin {
  to { transform: rotate(360deg); }
}

.terminal-view__hints {
  padding: 0 4px;
}

.terminal-view__hints code {
  background: rgba(15, 23, 42, 0.06);
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 12px;
}
</style>