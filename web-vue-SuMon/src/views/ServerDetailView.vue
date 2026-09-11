<template>
  <div class="server-detail-view">
    <PageHeader
      :title="`服务器详情${data ? ' #' + data.id : ''}`"
      :subtitle="data ? `${data.name} · ${data.host}` : '加载中...'"
    >
      <template #actions>
        <el-button @click="goBack">
          <svg
            viewBox="0 0 24 24"
            xmlns="http://www.w3.org/2000/svg"
            aria-hidden="true"
            class="server-detail-view__back-icon"
          >
            <path
              d="M14 6 L8 12 L14 18"
              fill="none"
              stroke="currentColor"
              stroke-width="2.2"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
          <span>返回</span>
        </el-button>
        <el-button
          type="primary"
          plain
          :disabled="!data"
          @click="openEdit"
        >
          编辑
        </el-button>
        <el-button
          v-if="auth.isAdmin"
          type="danger"
          plain
          :disabled="!data"
          class="server-detail-view__ai"
          @click="openAiDiagnosis"
        >
          AI 智能诊断
        </el-button>
        <el-button
          type="success"
          plain
          :disabled="!data"
          class="server-detail-view__metrics"
          @click="goMetrics"
        >
          实时监控
        </el-button>
        <el-button
          v-if="auth.isApproved"
          type="warning"
          plain
          :disabled="!data"
          class="server-detail-view__terminal"
          @click="openTerminal"
        >
          Web 终端
        </el-button>
        <el-button
          v-if="auth.isAdmin"
          type="success"
          plain
          :disabled="!data || agentBusy"
          :loading="agentBusy"
          class="server-detail-view__host-key-trust"
          @click="trustHostKeyOneClick"
        >
          一键信任主机
        </el-button>
        <el-button
          v-if="auth.isAdmin"
          type="info"
          plain
          :disabled="!data"
          class="server-detail-view__host-key"
          @click="openHostKeyDialog"
        >
          主机指纹确认
        </el-button>
        <el-popconfirm
          :title="data ? `确定要删除 ${data.name} 吗?` : '确定要删除吗?'"
          confirm-button-text="删除"
          cancel-button-text="取消"
          @confirm="handleDelete"
        >
          <template #reference>
            <el-button
              type="danger"
              plain
              :disabled="!data"
            >
              删除
            </el-button>
          </template>
        </el-popconfirm>
      </template>
    </PageHeader>

    <div
      v-if="loading"
      v-loading="loading"
      class="server-detail-view__loading"
    />

    <el-row
      v-else-if="data"
      :gutter="20"
    >
      <el-col
        :xs="24"
        :md="12"
      >
        <el-card
          class="server-detail-view__card"
          shadow="never"
        >
          <template #header>
            <div class="server-detail-view__card-title">
              <TushanFoxMark
                :size="28"
                alt="涂山苏苏·服务器基本信息"
              />
              基本信息
            </div>
          </template>
          <el-descriptions
            :column="1"
            class="server-detail-view__desc"
          >
            <el-descriptions-item label="名称">
              {{ data.name }}
            </el-descriptions-item>
            <el-descriptions-item label="主机地址">
              {{ data.host }}
            </el-descriptions-item>
            <el-descriptions-item label="描述">
              {{ data.description || '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="SSH 主机">
              {{ data.ssh_host }}
            </el-descriptions-item>
            <el-descriptions-item label="SSH 端口">
              {{ data.ssh_port }}
            </el-descriptions-item>
            <el-descriptions-item label="SSH 用户名">
              {{ data.ssh_user }}
            </el-descriptions-item>
            <el-descriptions-item label="认证方式">
              {{ data.ssh_auth_type === 'password' ? '密码' : '私钥' }}
            </el-descriptions-item>
            <el-descriptions-item label="Agent ID">
              {{ data.agent_id || '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">
              {{ formatDateTime(data.created_at) }}
            </el-descriptions-item>
            <el-descriptions-item label="更新时间">
              {{ formatDateTime(data.updated_at) }}
            </el-descriptions-item>
          </el-descriptions>

          <div class="server-detail-view__ssh-actions">
            <el-button
              plain
              @click="handleTestConnection"
            >
              <svg
                viewBox="0 0 24 24"
                xmlns="http://www.w3.org/2000/svg"
                aria-hidden="true"
                class="server-detail-view__ssh-icon"
              >
                <path
                  d="M4 12 L8 12 M16 12 L20 12 M12 4 L12 8 M12 16 L12 20"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                />
                <circle
                  cx="12"
                  cy="12"
                  r="3"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                />
              </svg>
              测试连接
            </el-button>
            <span class="server-detail-view__ssh-hint">
              后端 MVP-7 接入后启用真实 SSH 测试
            </span>
          </div>
        </el-card>
      </el-col>

      <el-col
        :xs="24"
        :md="12"
      >
        <el-card
          class="server-detail-view__card"
          shadow="never"
        >
          <template #header>
            <div class="server-detail-view__card-title">
              <TushanFoxMark
                :size="28"
                alt="涂山苏苏·状态快照"
              />
              状态快照
            </div>
          </template>

          <div
            v-if="status"
            class="server-detail-view__status-grid"
          >
            <div class="server-detail-view__status-item">
              <span class="server-detail-view__status-label">服务器状态</span>
              <span
                class="server-detail-view__badge"
                :class="`server-detail-view__badge--${status.status}`"
              >
                {{ serverStatusLabel(status.status) }}
              </span>
            </div>
            <div class="server-detail-view__status-item">
              <span class="server-detail-view__status-label">Agent 状态</span>
              <span
                class="server-detail-view__badge"
                :class="`server-detail-view__badge--${status.agent_status}`"
              >
                {{ status.agent_status }}
              </span>
            </div>
            <div class="server-detail-view__status-item">
              <span class="server-detail-view__status-label">上次心跳</span>
              <span class="server-detail-view__status-value">
                {{ formatDateTime(status.last_heartbeat_at) }}
              </span>
            </div>
            <div class="server-detail-view__status-item">
              <span class="server-detail-view__status-label">投递积压</span>
              <span class="server-detail-view__status-value">
                {{ formatDeliveryPending(status) }}
              </span>
            </div>
            <div class="server-detail-view__status-item">
              <span class="server-detail-view__status-label">最旧积压采样</span>
              <span class="server-detail-view__status-value">
                {{ formatDateTime(status.delivery_oldest_collected_at) }}
              </span>
            </div>
            <div class="server-detail-view__status-item">
              <span class="server-detail-view__status-label">缓冲丢弃</span>
              <span class="server-detail-view__status-value">
                {{ status.delivery_drop_count ?? '-' }}
              </span>
            </div>
            <div class="server-detail-view__status-item">
              <span class="server-detail-view__status-label">本地死信</span>
              <span class="server-detail-view__status-value">
                {{ formatDeliveryDeadLetter(status) }}
              </span>
            </div>
            <div class="server-detail-view__status-item">
              <span class="server-detail-view__status-label">查询时间</span>
              <span class="server-detail-view__status-value">
                {{ formatDateTime(status.checked_at) }}
              </span>
            </div>
          </div>

          <div
            v-else
            class="server-detail-view__status-empty"
          >
            暂无状态快照
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card
      v-if="auth.isAdmin && data"
      class="server-detail-view__card"
      shadow="never"
    >
      <template #header>
        <div class="server-detail-view__card-title">
          <TushanFoxMark
            :size="28"
            alt="涂山苏苏·Agent Token 管理"
          />
          Agent Token 管理
        </div>
      </template>
      <div class="server-detail-view__agent-actions">
        <el-button
          type="primary"
          plain
          :disabled="agentBusy"
          @click="openAgentTokenDialog('register')"
        >
          生成 Token
        </el-button>
        <el-button
          type="warning"
          plain
          :disabled="agentBusy"
          @click="openAgentTokenDialog('rotate')"
        >
          轮换 Token
        </el-button>
        <el-popconfirm
          title="撤销 Token 后 Agent 将立即断开,确认继续?"
          confirm-button-text="撤销"
          cancel-button-text="取消"
          @confirm="handleRevokeToken"
        >
          <template #reference>
            <el-button
              type="danger"
              plain
              :disabled="agentBusy"
            >
              撤销 Token
            </el-button>
          </template>
        </el-popconfirm>
        <span class="server-detail-view__agent-hint">
          仅管理员可操作;明文 Token 仅生成 / 轮换响应中出现一次,关闭对话框后无法再查。
        </span>
      </div>
    </el-card>

    <ServerFormDialog
      v-model="editOpen"
      :server="data"
      @success="reload"
    />

    <AiDiagnosisDialog
      v-model="aiDiagnosisOpen"
      :server-id="data?.id ?? null"
      :server-name="data?.name ?? ''"
    />

    <AgentTokenDialog
      v-model="agentDialogOpen"
      :mode="agentDialogMode"
      :server-id="data?.id ?? 0"
      :server-name="data?.name"
      @success="handleAgentTokenSuccess"
    />
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import ServerFormDialog from '@/components/ServerFormDialog.vue'
import AgentTokenDialog from '@/components/AgentTokenDialog.vue'
import AiDiagnosisDialog from '@/components/AiDiagnosisDialog.vue'
import TushanFoxMark from '@/components/TushanFoxMark.vue'
import { ApiBusinessError } from '@/api/client'
import { confirmSshHostKey, deleteServer, getServer, getServerStatus, observeSshHostKey, testSshConnection } from '@/api/server'
import { revokeAgentToken } from '@/api/agent-token'
import { ErrorCode } from '@/types/error-code'
import { useAuthStore } from '@/stores/auth'
import type { Server, ServerStatus, SshHostKey, SshTestResult } from '@/types/api'
import { formatBytes, formatDateTime, serverStatusLabel } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const loading = ref(false)
const data = ref<Server | null>(null)
const status = ref<ServerStatus | null>(null)
const editOpen = ref(false)
const aiDiagnosisOpen = ref(false)
const agentDialogOpen = ref(false)
const agentDialogMode = ref<'register' | 'rotate'>('register')
const agentBusy = ref(false)
const STATUS_REFRESH_INTERVAL_MS = 30_000
let statusRefreshTimer: ReturnType<typeof setInterval> | null = null

/**
 * 解析路由参数 id(只接受数字,非法 id 直接跳回列表)。
 */
function parseId(): number | null {
  const raw = route.params.serverId
  const str = Array.isArray(raw) ? raw[0] : raw
  const n = Number(str)
  if (!Number.isFinite(n) || n <= 0) {
    return null
  }
  return n
}

/**
 * 拉取单台服务器 + 状态快照;任一返回 40400 → 跳回列表 + toast。
 */
async function reload(): Promise<void> {
  const id = parseId()
  if (id === null) {
    goBack()
    return
  }
  loading.value = true
  try {
    const [serverRes, statusRes] = await Promise.all([
      getServer(id),
      getServerStatus(id).catch(() => null)
    ])
    data.value = serverRes.data
    status.value = statusRes?.data ?? null
  } catch (error) {
    if (error instanceof ApiBusinessError && error.code === ErrorCode.RESOURCE_NOT_FOUND) {
      ElMessage.warning('服务器不存在或已被删除')
      goBack()
      return
    }
    ElMessage.error(explainError(error))
    data.value = null
  } finally {
    loading.value = false
  }
}

/** 每 30 秒刷新状态快照，失败时保留最后一次成功结果。 */
function startStatusRefresh(): void {
  statusRefreshTimer = setInterval(() => {
    const id = parseId()
    if (id === null) return
    void getServerStatus(id)
      .then((response) => {
        status.value = response.data
      })
      .catch(() => {
        // 自动刷新失败时不打断当前页面，也不重复弹出错误提示。
      })
  }, STATUS_REFRESH_INTERVAL_MS)
}

/** 投递积压展示:条数 + 字节;统计缺失(老版本 Agent)时显示 '-'. */
function formatDeliveryPending(snapshot: ServerStatus | null): string {
  if (!snapshot || snapshot.delivery_pending_count === null) {
    return '-'
  }
  return `${snapshot.delivery_pending_count} 条 / ${formatBytes(snapshot.delivery_pending_bytes)}`
}

/** 本地死信展示:条数 + 字节;统计缺失(老版本 Agent)时显示 '-'. */
function formatDeliveryDeadLetter(snapshot: ServerStatus | null): string {
  if (!snapshot || snapshot.delivery_dead_letter_count === null) {
    return '-'
  }
  return `${snapshot.delivery_dead_letter_count} 条 / ${formatBytes(snapshot.delivery_dead_letter_bytes)}`
}

function goBack(): void {
  void router.replace({ name: 'servers' })
}

function openEdit(): void {
  editOpen.value = true
}

function openAiDiagnosis(): void {
  aiDiagnosisOpen.value = true
}

function goMetrics(): void {
  if (data.value === null) return
  void router.push({ name: 'server-metrics', params: { serverId: data.value.id } })
}

/** 打开 Web 终端(MVP-7 T4)。按钮已在 approved 权限下显示,后端再校验。 */
function openTerminal(): void {
  if (data.value === null) return
  void router.push({ name: 'terminal', params: { serverId: data.value.id } })
}

function handleTestConnection(): void {
  if (!data.value) {
    ElMessage.warning('服务器数据未加载,无法测试')
    return
  }
  const id = data.value.id
  void testSshConnection(id)
    .then((res: { data: SshTestResult }) => {
      const r = res.data
      if (r.connected) {
        ElMessage.success(
          `SSH 连接成功 (${r.duration_ms}ms) · 认证方式 ${r.auth_type}`
        )
      } else {
        ElMessage.warning('SSH 连接失败,后端未返回详细原因')
      }
    })
    .catch((error: unknown) => {
      if (error instanceof ApiBusinessError) {
        switch (error.code) {
          case ErrorCode.SSH_AUTHENTICATION_FAILED:
            ElMessage.error('SSH 认证失败:请检查用户名密码 / 私钥')
            return
          case ErrorCode.SSH_CONNECTION_TIMEOUT:
            ElMessage.error('SSH 连接超时:请检查网络或防火墙')
            return
          case ErrorCode.SSH_HOST_KEY_NOT_CONFIRMED:
            ElMessage.error('SSH 主机密钥未确认:请先在服务器端 trust 主机')
            return
          case ErrorCode.SSH_HOST_KEY_MISMATCH:
            ElMessage.error('SSH 主机密钥不匹配:可能存在中间人攻击')
            return
          case ErrorCode.SSH_TARGET_FORBIDDEN:
            ElMessage.error('SSH 目标地址被禁止:仅允许配置的网段')
            return
          case ErrorCode.SSH_CONNECTION_LIMIT_REACHED:
            ElMessage.error('SSH 连接数已达上限,请稍后重试')
            return
          case ErrorCode.SSH_CONNECTION_FAILED:
            ElMessage.error('SSH 连接失败:请检查主机端口与可达性')
            return
          case ErrorCode.FORBIDDEN:
            ElMessage.error('无权限:仅管理员可执行 SSH 测试')
            return
          case ErrorCode.UNAUTHORIZED:
            ElMessage.error('未登录或登录已过期')
            return
          default:
            ElMessage.error(error.message || 'SSH 测试失败')
            return
        }
      }
      ElMessage.error('SSH 测试失败:网络异常')
    })
}

async function handleDelete(): Promise<void> {
  if (!data.value) {
    return
  }
  try {
    await deleteServer(data.value.id)
    ElMessage.success(`已删除 ${data.value.name}`)
    goBack()
  } catch (error) {
    ElMessage.error(explainError(error))
  }
}

/**
 * 一键信任主机密钥：观察目标主机当前公钥 → 弹窗核对 → 确认后登记。
 *
 * 由后端只读握手读取实际公钥，前端展示算法与指纹供管理员显式确认
 * （等同 SSH 客户端首次连接的 yes/no）；已登记指纹不同时提示"密钥已变更"
 * 并携带 replace=true 完成显式轮换。
 */
async function trustHostKeyOneClick(): Promise<void> {
  if (!data.value) {
    ElMessage.warning('服务器数据未加载')
    return
  }
  agentBusy.value = true
  try {
    const observation = (await observeSshHostKey(data.value.id)).data
    const registered = observation.registered_fingerprint
    const changed = registered !== null && registered !== observation.host_key_fingerprint
    const message = changed
      ? `目标主机当前公钥与已登记的不一致（可能重装系统或密钥被替换）。\n\n算法: ${observation.host_key_algorithm}\n指纹: ${observation.host_key_fingerprint}\n\n确认以新密钥替换并信任？`
      : `检测到目标主机公钥。\n\n算法: ${observation.host_key_algorithm}\n指纹: ${observation.host_key_fingerprint}\n\n确认信任该主机？`
    let confirmed = false
    try {
      await ElMessageBox.confirm(message, changed ? '主机密钥已变更' : '确认信任主机密钥', {
        confirmButtonText: '确认信任',
        cancelButtonText: '取消',
        type: 'warning'
      })
      confirmed = true
    } catch {
      // 管理员取消，不登记。
    }
    if (!confirmed) {
      return
    }
    const response = await confirmSshHostKey(data.value.id, {
      expected_fingerprint: observation.host_key_fingerprint,
      replace: changed
    })
    const resultData: SshHostKey = response.data
    ElMessage.success(
      `指纹已${operationLabel(resultData.operation)}(算法 ${resultData.host_key_algorithm})`
    )
  } catch (error) {
    ElMessage.error(explainSshHostKeyError(error))
  } finally {
    agentBusy.value = false
  }
}

/**
 * 打开主机指纹确认流程。
 *
 * 使用 ElMessageBox.prompt 输入指纹;replace 复选框采用 ElMessageBox 不可用,
 * 退化为提示文案引导管理员"轮换请先在新 SSH 会话核验后再次确认"。
 * 真实带外核对必须由管理员在服务器控制台手动完成,前端无法承担安全责任。
 */
async function openHostKeyDialog(): Promise<void> {
  if (!data.value) {
    ElMessage.warning('服务器数据未加载')
    return
  }
  let result: { value: string }
  try {
    result = await ElMessageBox.prompt(
      `请输入通过 ssh-keyscan -lf <host> 取得的 OpenSSH SHA-256 指纹。\n格式:SHA256:<Base64 43 字符>\n服务器:${data.value.ssh_host}:${data.value.ssh_port}`,
      '确认 SSH 主机指纹',
      {
        confirmButtonText: '确认',
        cancelButtonText: '取消',
        inputPattern: /^SHA256:[A-Za-z0-9+/]{43}$/,
        inputErrorMessage: '指纹格式应为 SHA256:Base43(无填充)'
      }
    )
  } catch {
    return
  }
  const fingerprint = (result.value ?? '').trim()
  if (fingerprint === '') {
    return
  }
  // 轮换意图:再次确认时若指纹与已登记不一致,前端不强制 require replace=true,
  // 把"轮换还是首次确认"决策权留给后端 (operation 字段会回传 "confirmed"/"rotated"/"unchanged")。
  agentBusy.value = true
  try {
    const response = await confirmSshHostKey(data.value.id, {
      expected_fingerprint: fingerprint,
      replace: false
    })
    const resultData: SshHostKey = response.data
    ElMessage.success(
      `指纹已${operationLabel(resultData.operation)}(算法 ${resultData.host_key_algorithm})`
    )
  } catch (error) {
    ElMessage.error(explainSshHostKeyError(error))
  } finally {
    agentBusy.value = false
  }
}

function operationLabel(operation: SshHostKey['operation']): string {
  if (operation === 'confirmed') return '确认'
  if (operation === 'rotated') return '轮换'
  return '复核'
}

function explainSshHostKeyError(error: unknown): string {
  if (error instanceof ApiBusinessError) {
    switch (error.code) {
      case ErrorCode.SSH_HOST_KEY_MISMATCH:
        return '指纹与服务器不匹配:请确认带外取得的指纹正确'
      case ErrorCode.SSH_TARGET_FORBIDDEN:
        return '目标地址被禁止:仅允许配置的网段'
      case ErrorCode.SSH_CONNECTION_LIMIT_REACHED:
        return 'SSH 连接数已达上限,请稍后重试'
      case ErrorCode.SSH_CONNECTION_FAILED:
        return 'SSH 连接失败:请检查主机端口与可达性'
      case ErrorCode.SSH_CONNECTION_TIMEOUT:
        return 'SSH 连接超时'
      case ErrorCode.FORBIDDEN:
        return '无权限:仅管理员可确认主机指纹'
      case ErrorCode.UNAUTHORIZED:
        return '未登录或登录已过期'
      default:
        return error.message || '操作失败'
    }
  }
  return '网络异常,请稍后重试'
}

/**
 * 打开 Agent Token 管理对话框(生成 / 轮换)。
 *
 * @param mode register:首次生成;rotate:显式轮换(旧 Token 立即失效)
 */
function openAgentTokenDialog(mode: 'register' | 'rotate'): void {
  if (!data.value) {
    ElMessage.warning('服务器数据未加载')
    return
  }
  agentDialogMode.value = mode
  agentDialogOpen.value = true
}

/**
 * Agent Token 生成 / 轮换成功后的副作用。
 *
 * 当前为最小反馈:toast 已在 dialog 内部触发,父组件只刷新 Agent 状态快照,
 * 让 agent_status 反映新 Token 的有效性。
 */
async function handleAgentTokenSuccess(): Promise<void> {
  if (!data.value) return
  try {
    const statusRes = await getServerStatus(data.value.id)
    status.value = statusRes.data
  } catch {
    // 状态刷新失败不阻断;Agent 上线后再行更新
  }
}

async function handleRevokeToken(): Promise<void> {
  if (!data.value) return
  agentBusy.value = true
  try {
    await revokeAgentToken(data.value.id)
    ElMessage.success('Token 已撤销,Agent 将立即断开')
    await reload()
  } catch (error) {
    if (error instanceof ApiBusinessError) {
      switch (error.code) {
        case ErrorCode.FORBIDDEN:
          ElMessage.error('无权限:仅管理员可撤销 Token')
          break
        case ErrorCode.UNAUTHORIZED:
          ElMessage.error('未登录或登录已过期')
          break
        case ErrorCode.RESOURCE_CONFLICT:
          ElMessage.error('Token 状态冲突,请刷新后重试')
          break
        case ErrorCode.RESOURCE_NOT_FOUND:
          ElMessage.error('服务器不存在或已被删除')
          break
        default:
          ElMessage.error(error.message || '撤销失败')
      }
    } else {
      ElMessage.error('网络异常,请稍后重试')
    }
  } finally {
    agentBusy.value = false
  }
}

function explainError(error: unknown): string {
  if (error instanceof ApiBusinessError) {
    if (error.code === ErrorCode.FORBIDDEN) {
      return '当前账号无权操作'
    }
    return error.message || '操作失败'
  }
  return '网络异常,请稍后重试'
}

watch(
  () => route.params.serverId,
  () => {
    void reload()
  }
)

onMounted(() => {
  void reload().finally(() => {
    startStatusRefresh()
  })
})

onBeforeUnmount(() => {
  if (statusRefreshTimer !== null) {
    clearInterval(statusRefreshTimer)
    statusRefreshTimer = null
  }
})
</script>

<style scoped>
.server-detail-view {
  max-width: 1280px;
  margin: 0 auto;
}

.server-detail-view__loading {
  min-height: 240px;
}

.server-detail-view__card {
  background: var(--susu-surface-soft);
  backdrop-filter: blur(20px) saturate(180%);
  -webkit-backdrop-filter: blur(20px) saturate(180%);
  border: 1px solid var(--susu-border-glass);
  border-radius: 16px;
  box-shadow:
    0 12px 32px rgba(183, 50, 92, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.85);
}

.server-detail-view__card :deep(.el-card__header) {
  padding: 16px 20px;
  border-bottom: 1px solid rgba(183, 50, 92, 0.08);
}

.server-detail-view__card :deep(.el-card__body) {
  padding: 20px;
}

.server-detail-view__card-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 700;
  color: var(--susu-ink);
}

.server-detail-view__back-icon {
  width: 16px;
  height: 16px;
  margin-right: 4px;
  vertical-align: -2px;
}

.server-detail-view__desc :deep(.el-descriptions__label) {
  color: #8a5872;
  font-weight: 600;
  width: 100px;
}

.server-detail-view__desc :deep(.el-descriptions__content) {
  color: var(--susu-ink);
  font-weight: 500;
}

.server-detail-view__ssh-actions {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px dashed rgba(183, 50, 92, 0.12);
  display: flex;
  align-items: center;
  gap: 12px;
}

.server-detail-view__ssh-icon {
  width: 16px;
  height: 16px;
  margin-right: 4px;
  vertical-align: -2px;
}

.server-detail-view__ssh-hint {
  font-size: 11px;
  color: #8a5872;
  font-style: italic;
}

.server-detail-view__status-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 14px;
}

.server-detail-view__status-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  background: var(--susu-surface-soft);
  border-radius: 10px;
  border: 1px solid var(--susu-border-glass);
}

.server-detail-view__status-label {
  font-size: 13px;
  color: var(--susu-ink-muted);
  letter-spacing: 0.5px;
}

.server-detail-view__status-value {
  font-size: 13px;
  color: var(--susu-ink);
  font-weight: 500;
}

.server-detail-view__badge {
  display: inline-block;
  padding: 4px 12px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 1px;
  color: #fff;
}

.server-detail-view__badge--online {
  background: linear-gradient(135deg, #66e6a8 0%, #2eb872 100%);
  box-shadow: 0 2px 8px rgba(46, 184, 114, 0.35);
}

.server-detail-view__badge--offline {
  background: linear-gradient(135deg, #9ca3af 0%, #6b7280 100%);
  box-shadow: 0 2px 8px rgba(107, 114, 128, 0.3);
}

.server-detail-view__badge--unknown {
  background: linear-gradient(135deg, #f5b942 0%, #d97706 100%);
  box-shadow: 0 2px 8px rgba(217, 119, 6, 0.35);
}

.server-detail-view__status-empty {
  padding: 32px;
  text-align: center;
  color: #8a5872;
  font-size: 13px;
}

.server-detail-view__agent-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.server-detail-view__agent-hint {
  font-size: 11px;
  color: #8a5872;
  font-style: italic;
  flex: 1 1 100%;
  margin-top: 4px;
}
</style>