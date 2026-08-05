<template>
  <div class="alert-records-view">
    <PageHeader
      title="告警记录"
      subtitle="所有触发的告警历史；选择具体服务器后可通过 /ws/monitor 接收该服务器的实时 alert.push 提示"
    >
      <template #actions>
        <el-tag :type="wsConnectionTagType">
          {{ wsConnectionLabel }}
        </el-tag>
        <el-button
          type="primary"
          plain
          :loading="alerts.recordsLoading"
          @click="reload"
        >
          刷新
        </el-button>
      </template>
    </PageHeader>

    <el-alert
      v-if="alerts.pendingPushCount > 0 && hasServerFilter"
      type="info"
      show-icon
      class="alert-records-view__push-banner"
      :closable="false"
    >
      <template #title>
        有 {{ alerts.pendingPushCount }} 条新告警到达
        <el-link
          type="primary"
          :underline="false"
          @click="onPushBannerRefresh"
        >
          点此刷新
        </el-link>
      </template>
    </el-alert>

    <el-alert
      v-if="alerts.wsError"
      :title="alerts.wsError"
      type="warning"
      show-icon
      class="alert-records-view__ws-error"
      :closable="false"
    />

    <el-card
      class="alert-records-view__card"
      shadow="never"
    >
      <div class="alert-records-view__filters">
        <el-select
          v-model="serverFilter"
          placeholder="服务器"
          clearable
          class="alert-records-view__filter"
          @change="onServerFilterChange"
        >
          <el-option
            v-for="item in serverOptions"
            :key="item.id"
            :label="item.name"
            :value="item.id"
          />
        </el-select>
        <el-select
          v-model="statusFilter"
          placeholder="状态"
          class="alert-records-view__filter"
          @change="onStatusFilterChange"
        >
          <el-option
            label="全部"
            value="all"
          />
          <el-option
            label="未读"
            value="unread"
          />
          <el-option
            label="已读"
            value="read"
          />
          <el-option
            label="已解决"
            value="resolved"
          />
        </el-select>
        <span class="alert-records-view__filter-hint">
          实时推送仅在选择具体服务器时启用；"全部服务器" 模式下不会订阅 /ws/monitor
        </span>
      </div>

      <el-table
        v-loading="alerts.recordsLoading"
        :data="alerts.records"
        stripe
        empty-text="当前筛选条件下暂无告警"
      >
        <el-table-column
          prop="triggered_at"
          label="触发时间"
          min-width="170"
        >
          <template #default="{ row }">
            {{ formatDateTime(row.triggered_at) }}
          </template>
        </el-table-column>
        <el-table-column
          prop="server_id"
          label="服务器"
          width="100"
        />
        <el-table-column
          prop="metric"
          label="指标"
          width="110"
        />
        <el-table-column
          label="当前值 / 阈值"
          min-width="150"
        >
          <template #default="{ row }">
            <span class="alert-records-view__value">{{ formatNumber(row.current_value) }}</span>
            <span class="alert-records-view__sep">/</span>
            <span class="alert-records-view__threshold">{{ formatNumber(row.threshold_value) }}</span>
          </template>
        </el-table-column>
        <el-table-column
          prop="level"
          label="等级"
          width="100"
        >
          <template #default="{ row }">
            <el-tag :type="levelTagType(row.level)">
              {{ levelLabel(row.level) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="status"
          label="状态"
          width="100"
        >
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="通知状态"
          min-width="150"
        >
          <template #default="{ row }">
            <span v-if="row.notify_channels">
              {{ channelLabel(row.notify_channels) }}
            </span>
            <span
              v-else
              class="alert-records-view__not-failed"
            >
              未发送
            </span>
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="120"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'unread'"
              size="small"
              type="primary"
              plain
              :loading="markingReadId === row.id"
              @click="handleMarkRead(row as AlertRecord)"
            >
              标记已读
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty
        v-if="!alerts.recordsLoading && alerts.records.length === 0"
        description="当前筛选条件下暂无告警"
      />

      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :total="alerts.recordsTotal"
        :page-sizes="pageSizeOptions"
        layout="total, sizes, prev, pager, next, jumper"
        class="alert-records-view__pagination"
        @current-change="onPageChange"
        @size-change="onPageSizeChange"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import { listServers } from '@/api/server'
import { useAlertsStore } from '@/stores/alerts'
import { MonitorWebSocket } from '@/services/websocket'
import { formatDateTime } from '@/utils/format'
import type { AlertRecord, AlertRecordQuery, AlertStatus, Server } from '@/types/api'

const alerts = useAlertsStore()

/** 筛选与分页状态 */
const serverOptions = ref<Server[]>([])
const serverFilter = ref<number | null>(null)
const statusFilter = ref<'all' | AlertStatus>('all')
const page = ref(1)
const pageSizeOptions: number[] = [10, 20, 50, 100]
const pageSize = ref<number>(pageSizeOptions[0])
const markingReadId = ref<number | null>(null)

/** 实时连接 WS(按需创建);切换筛选或离页时必须 disconnect。 */
let socket: MonitorWebSocket | null = null

/** 当前 serverFilter 是否选了具体 ID(决定是否建立 WS)。 */
const hasServerFilter = computed<boolean>(
  () => typeof serverFilter.value === 'number' && Number.isFinite(serverFilter.value)
)

const wsConnectionTagType = computed<'success' | 'info' | 'warning'>(() => {
  if (!hasServerFilter.value) return 'info'
  return alerts.wsConnected ? 'success' : 'warning'
})
const wsConnectionLabel = computed<string>(() => {
  if (!hasServerFilter.value) return '未订阅'
  return alerts.wsConnected ? '实时连接' : '实时断开'
})

function levelLabel(level: string): string {
  if (level === 'critical') return '严重'
  return '警告'
}
function levelTagType(level: string): 'danger' | 'warning' {
  return level === 'critical' ? 'danger' : 'warning'
}
function statusLabel(status: AlertStatus | string): string {
  if (status === 'read') return '已读'
  if (status === 'resolved') return '已解决'
  return '未读'
}
function statusTagType(status: AlertStatus | string): 'success' | 'info' | 'warning' {
  if (status === 'read') return 'info'
  if (status === 'resolved') return 'success'
  return 'warning'
}
function formatNumber(value: number): string {
  if (typeof value !== 'number' || Number.isNaN(value)) return '-'
  return Number.isInteger(value) ? value.toString() : value.toFixed(2)
}

/** 将成功渠道字符串（email,dingtalk,webhook）转为可读文案。 */
function channelLabel(channels: string): string {
  return channels
    .split(',')
    .map((channel) => {
      if (channel === 'email') return '📧 邮件'
      if (channel === 'dingtalk') return '💬 钉钉'
      if (channel === 'webhook') return '🔗 Webhook'
      return channel
    })
    .join(' / ')
}

/**
 * 把当前筛选状态合并为后端查询参数。
 * status='all' 必须从 query 中剔除,后端仅接受 AlertStatus 枚举值。
 */
function buildQuery(): AlertRecordQuery {
  const q: AlertRecordQuery = {
    page: page.value,
    page_size: pageSize.value,
    server_id: hasServerFilter.value ? (serverFilter.value as number) : undefined
  }
  if (statusFilter.value !== 'all') {
    q.status = statusFilter.value
  }
  return q
}

async function loadServerOptions(): Promise<void> {
  try {
    const response = await listServers({ page: 1, page_size: 100, sort_by: 'id', sort_order: 'asc' })
    serverOptions.value = response.data?.items ?? []
  } catch {
    // 服务器下拉为空不影响主表格,静默忽略
    serverOptions.value = []
  }
}

async function reload(): Promise<void> {
  await alerts.loadRecords(buildQuery())
}

/**
 * 断开旧 WS,按 serverFilter 决定是否再建立新 WS。
 * 调用时机:onMounted、serverFilter watcher、onBeforeUnmount。
 */
function resyncWebSocket(): void {
  if (socket !== null) {
    socket.disconnect()
    socket = null
  }
  if (!hasServerFilter.value) {
    alerts.setWsConnected(false)
    alerts.setWsError(null)
    return
  }
  const target = serverFilter.value as number
  socket = new MonitorWebSocket(
    () => undefined,
    (connected) => alerts.setWsConnected(connected),
    (payload) => alerts.applyAlertPush(payload)
  )
  alerts.setWsError(null)
  socket.connect(target)
}

watch(serverFilter, () => {
  // 切换筛选:WS 重连 + REST 重拉,页码重置。
  page.value = 1
  alerts.markPendingPushSeen()
  resyncWebSocket()
  void reload()
})

watch(statusFilter, () => {
  page.value = 1
  alerts.markPendingPushSeen()
  void reload()
})

function onServerFilterChange(_value: number | null | undefined): void {
  // watch 已经处理;此处保留钩子供模板 @change 使用,无副作用。
}
function onStatusFilterChange(_value: 'all' | AlertStatus): void {
  // 同上
}

function onPageChange(next: number): void {
  page.value = next
  void reload()
}
function onPageSizeChange(next: number): void {
  pageSize.value = next
  page.value = 1
  void reload()
}

async function handleMarkRead(row: AlertRecord): Promise<void> {
  markingReadId.value = row.id
  const ok = await alerts.markRead(row.id)
  markingReadId.value = null
  if (ok) {
    ElMessage.success('已标记为已读')
  }
}

function onPushBannerRefresh(): void {
  alerts.markPendingPushSeen()
  void reload()
}

onMounted(async () => {
  await loadServerOptions()
  await reload()
  // 仅当默认有 serverFilter(默认 null 时)才建立 WS;默认 null 不订阅。
  resyncWebSocket()
})

onBeforeUnmount(() => {
  if (socket !== null) {
    socket.disconnect()
    socket = null
  }
  alerts.resetRecords()
})
</script>

<style scoped>
.alert-records-view {
  max-width: 1280px;
  margin: 0 auto;
}

.alert-records-view__card {
  background: rgba(255, 255, 255, 0.5);
  backdrop-filter: blur(20px) saturate(180%);
  -webkit-backdrop-filter: blur(20px) saturate(180%);
  border: 1px solid rgba(255, 255, 255, 0.7);
  border-radius: 16px;
  box-shadow:
    0 12px 32px rgba(183, 50, 92, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.85);
}

.alert-records-view__card :deep(.el-card__body) {
  padding: 20px;
}

.alert-records-view__push-banner,
.alert-records-view__ws-error {
  margin-bottom: 12px;
}

.alert-records-view__filters {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.alert-records-view__filter {
  width: 200px;
}

.alert-records-view__filter-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.alert-records-view__value {
  font-weight: 600;
  color: var(--el-color-danger);
}

.alert-records-view__sep {
  margin: 0 6px;
  color: var(--el-text-color-secondary);
}

.alert-records-view__threshold {
  color: var(--el-text-color-secondary);
}

.alert-records-view__pagination {
  margin-top: 16px;
  justify-content: flex-end;
}

.alert-records-view__not-failed {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>