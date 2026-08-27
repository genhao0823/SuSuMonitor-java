<template>
  <div class="alert-rules-view">
    <PageHeader
      title="告警规则"
      subtitle="基于指标阈值的告警触发规则；仅管理员可创建 / 编辑 / 启停 / 删除"
    >
      <template #actions>
        <el-button
          v-if="auth.isAdmin"
          type="primary"
          class="alert-rules-view__create"
          @click="openCreate"
        >
          <svg
            viewBox="0 0 24 24"
            xmlns="http://www.w3.org/2000/svg"
            aria-hidden="true"
          >
            <path
              d="M12 4 L12 20 M4 12 L20 12"
              fill="none"
              stroke="currentColor"
              stroke-width="2.4"
              stroke-linecap="round"
            />
          </svg>
          <span>新建规则</span>
        </el-button>
      </template>
    </PageHeader>

    <el-alert
      v-if="alerts.rulesError"
      :title="alerts.rulesError"
      type="error"
      show-icon
      :closable="false"
    />

    <el-card
      class="alert-rules-view__card"
      shadow="never"
    >
      <el-table
        v-loading="alerts.rulesLoading"
        :data="alerts.rules"
        stripe
        empty-text="暂无告警规则，点击右上角创建"
      >
        <el-table-column
          prop="id"
          label="ID"
          width="70"
        />
        <el-table-column
          label="适用服务器"
          min-width="180"
        >
          <template #default="{ row }">
            <span v-if="row.server_id === null">全局规则</span>
            <span v-else>{{ serverName(row.server_id) }} <span class="alert-rules-view__sid">(#{{ row.server_id }})</span></span>
          </template>
        </el-table-column>
        <el-table-column
          prop="metric"
          label="指标"
          width="120"
        >
          <template #default="{ row }">
            {{ metricLabel(row.metric) }}
          </template>
        </el-table-column>
        <el-table-column
          prop="operator"
          label="运算符"
          width="80"
        />
        <el-table-column
          prop="threshold_value"
          label="阈值"
          width="90"
        />
        <el-table-column
          prop="level"
          label="等级"
          width="100"
        >
          <template #default="{ row }">
            <el-tag :type="row.level === 'critical' ? 'danger' : 'warning'">
              {{ row.level === 'critical' ? '严重' : '警告' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="确认次数"
          width="120"
        >
          <template #default="{ row }">
            <el-tag
              v-if="(row.confirm_count ?? 1) > 1"
              type="info"
              effect="plain"
            >
              连续 {{ row.confirm_count }} 次
            </el-tag>
            <span v-else>1（立即触发）</span>
          </template>
        </el-table-column>
        <el-table-column
          label="通知方式"
          min-width="160"
        >
          <template #default="{ row }">
            <template v-if="ruleChannels(row as AlertRule).length > 0">
              <el-tag
                v-for="channel in ruleChannels(row as AlertRule)"
                :key="channel"
                size="small"
                effect="plain"
                class="alert-rules-view__channel-tag"
              >
                {{ channelLabel(channel) }}
              </el-tag>
            </template>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column
          label="启用"
          width="90"
        >
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              :disabled="!auth.isAdmin || togglingId === row.id"
              @change="(v: boolean | string | number) => handleToggleEnabled(row as AlertRule, Boolean(v))"
            />
          </template>
        </el-table-column>
        <el-table-column
          v-if="auth.isAdmin"
          label="操作"
          width="180"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button
              size="small"
              type="primary"
              plain
              @click="openEdit(row as AlertRule)"
            >
              编辑
            </el-button>
            <el-popconfirm
              :title="`确定删除规则 #${row.id} 吗？`"
              confirm-button-text="删除"
              cancel-button-text="取消"
              @confirm="handleDelete(row as AlertRule)"
            >
              <template #reference>
                <el-button
                  size="small"
                  type="danger"
                  plain
                >
                  删除
                </el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>

      <el-empty
        v-if="!alerts.rulesLoading && alerts.rules.length === 0 && !auth.isAdmin"
        description="暂无告警规则；如需创建请联系管理员"
      />
    </el-card>

    <AlertRuleDialog
      v-if="auth.isAdmin"
      v-model="dialogVisible"
      :rule="editingRule"
      :server-options="serverOptions"
      @success="onDialogSuccess"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import AlertRuleDialog from '@/components/AlertRuleDialog.vue'
import { listServers } from '@/api/server'
import { useAlertsStore } from '@/stores/alerts'
import { useAuthStore } from '@/stores/auth'
import { ApiBusinessError } from '@/api/client'
import { ErrorCode } from '@/types/error-code'
import type { AlertMetric, AlertRule, Server } from '@/types/api'

const auth = useAuthStore()
const alerts = useAlertsStore()

const serverOptions = ref<Server[]>([])
const dialogVisible = ref(false)
const editingRule = ref<AlertRule | null>(null)
const togglingId = ref<number | null>(null)

function metricLabel(metric: string): string {
  switch (metric as AlertMetric) {
    case 'cpu':
      return 'CPU 使用率'
    case 'memory':
      return '内存使用率'
    case 'disk':
      return '磁盘使用率'
    case 'temperature':
      return '温度'
    case 'load':
      return '系统负载'
    default:
      return metric
  }
}

/** 返回规则已配置的非空通知渠道列表。 */
function ruleChannels(rule: AlertRule): string[] {
  const channels: string[] = []
  if (rule.notify_email) channels.push('email')
  if (rule.notify_dingtalk) channels.push('dingtalk')
  if (rule.notify_webhook) channels.push('webhook')
  return channels
}

function channelLabel(channel: string): string {
  if (channel === 'email') return '📧 邮件'
  if (channel === 'dingtalk') return '💬 钉钉'
  if (channel === 'webhook') return '🔗 Webhook'
  return channel
}

function serverName(serverId: number): string {
  const found = serverOptions.value.find((s) => s.id === serverId)
  return found?.name ?? `#${serverId}`
}

async function loadServerOptions(): Promise<void> {
  try {
    const response = await listServers({ page: 1, page_size: 100, sort_by: 'id', sort_order: 'asc' })
    serverOptions.value = response.data?.items ?? []
  } catch {
    serverOptions.value = []
  }
}

async function reloadRules(): Promise<void> {
  await alerts.loadRules()
}

async function onDialogSuccess(): Promise<void> {
  // Store 已经在 dialog 内同步;此处仅用于保留钩子(必要时重拉)。
  await reloadRules()
}

function openCreate(): void {
  editingRule.value = null
  dialogVisible.value = true
}

function openEdit(row: AlertRule): void {
  editingRule.value = row
  dialogVisible.value = true
}

async function handleToggleEnabled(row: AlertRule, next: boolean): Promise<void> {
  togglingId.value = row.id
  try {
    const updated = await alerts.updateRule(row.id, {
      threshold_value: row.threshold_value,
      level: (row.level as 'warning' | 'critical') ?? 'warning',
      enabled: next
    })
    if (updated === null) {
      ElMessage.error(alerts.rulesError ?? '切换失败')
    }
  } finally {
    togglingId.value = null
  }
}

async function handleDelete(row: AlertRule): Promise<void> {
  const ok = await alerts.deleteRule(row.id)
  if (ok) {
    ElMessage.success(`规则 #${row.id} 已删除`)
  } else {
    // ApiBusinessError 的 40400 已由 client 拦截;此处仅处理其他场景
    const err = alerts.rulesError
    if (err === null) {
      // 删除成功但 store 状态空,无需提示
    } else if (err.includes('not found') || err.includes('不存在')) {
      ElMessage.warning('规则不存在或已被删除,正在刷新列表')
      await reloadRules()
    } else {
      ElMessage.error(err)
    }
  }
  // 兜底静默
  void ApiBusinessError
  void ErrorCode
}

onMounted(async () => {
  await loadServerOptions()
  await reloadRules()
})
</script>

<style scoped>
.alert-rules-view__channel-tag {
  margin-right: 4px;
}
.alert-rules-view {
  max-width: 1280px;
  margin: 0 auto;
}

.alert-rules-view__card {
  background: rgba(255, 255, 255, 0.5);
  backdrop-filter: blur(20px) saturate(180%);
  -webkit-backdrop-filter: blur(20px) saturate(180%);
  border: 1px solid rgba(255, 255, 255, 0.7);
  border-radius: 16px;
  box-shadow:
    0 12px 32px rgba(183, 50, 92, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.85);
}

.alert-rules-view__card :deep(.el-card__body) {
  padding: 20px;
}

.alert-rules-view__create {
  background: linear-gradient(135deg, #ff5b8a 0%, #b7325c 100%) !important;
  border: none !important;
  box-shadow: 0 6px 14px rgba(255, 91, 138, 0.3) !important;
}

.alert-rules-view__create:hover {
  background: linear-gradient(135deg, #ff7aa3 0%, #c8426f 100%) !important;
  box-shadow: 0 10px 20px rgba(255, 91, 138, 0.45) !important;
}

.alert-rules-view__create svg {
  width: 16px;
  height: 16px;
  margin-right: 4px;
  vertical-align: -2px;
}

.alert-rules-view__sid {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>