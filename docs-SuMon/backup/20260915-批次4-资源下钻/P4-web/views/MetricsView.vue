<template>
  <div class="metrics-view">
    <PageHeader
      :title="`服务器 ${serverId} 监控`"
      subtitle="固定宽表指标实时与历史数据"
    >
      <template #actions>
        <el-tag :type="metrics.connected ? 'success' : 'warning'">
          {{ metrics.connected ? '实时连接' : '连接断开' }}
        </el-tag>
        <el-tag :type="agentStatus === 'online' ? 'success' : 'info'">
          {{ agentStatus === 'online' ? 'Agent 在线' : 'Agent 离线' }}
        </el-tag>
      </template>
    </PageHeader>
    <el-alert
      v-if="metrics.error"
      :title="metrics.error"
      type="error"
      show-icon
    />
    <el-row
      v-loading="metrics.loading"
      :gutter="12"
      class="metric-cards"
    >
      <el-col
        v-for="card in cards"
        :key="card.label"
        :xs="12"
        :sm="8"
        :md="4"
      >
        <el-card shadow="hover">
          <div class="metric-label">
            {{ card.label }}
          </div><strong>{{ card.value }}</strong>
        </el-card>
      </el-col>
    </el-row>
    <ProcessTopCard :snapshot="processSnapshot" />
    <el-card
      shadow="never"
      class="history-card"
    >
      <template #header>
        <div class="history-card__header">
          <span>历史采样（{{ metrics.history.length }} 条）</span>
          <el-date-picker
            v-model="timeRangeModel"
            type="datetimerange"
            :shortcuts="timeShortcuts"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            size="small"
            @change="onTimeRangeChange"
          />
        </div>
      </template>
      <el-tabs v-model="activeTab">
        <el-tab-pane
          label="📈 趋势图"
          name="chart"
        >
          <MetricsLineChart
            :data="metrics.history"
            :metrics="['cpu_percent', 'memory_percent', 'disk_percent']"
            :rules="alertRules"
            :server-id="serverId"
            title="CPU / 内存 / 磁盘使用率"
          />
          <MetricsLineChart
            :data="metrics.history"
            :metrics="['net_rx', 'net_tx']"
            :rules="alertRules"
            :server-id="serverId"
            title="网络 I/O（字节 / 采集周期）"
            height="240px"
          />
          <el-empty
            v-if="metrics.history.length === 0 && !metrics.loading"
            description="当前时间范围暂无历史指标"
          />
        </el-tab-pane>
        <el-tab-pane
          label="📋 数据表"
          name="table"
        >
          <el-table
            :data="metrics.history"
            stripe
          >
            <el-table-column
              label="采集时间"
              min-width="190"
            >
              <template #default="{ row }">
                {{ formatDateTime(row.collected_at) }}
              </template>
            </el-table-column>
            <el-table-column
              prop="cpu_percent"
              label="CPU %"
            />
            <el-table-column
              prop="memory_percent"
              label="内存 %"
            />
            <el-table-column
              prop="disk_percent"
              label="磁盘 %"
            />
            <el-table-column
              prop="load_avg"
              label="Load"
            />
          </el-table>
          <el-empty
            v-if="metrics.history.length === 0 && !metrics.loading"
            description="暂无历史指标"
          />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import MetricsLineChart from '@/components/MetricsLineChart.vue'
import ProcessTopCard from '@/components/ProcessTopCard.vue'
import { useMetricsStore } from '@/stores/metrics'
import { MonitorWebSocket } from '@/services/websocket'
import { getLatestProcesses } from '@/api/metrics'
import { getServerStatus } from '@/api/server'
import { listAlertRules } from '@/api/alert'
import type { AgentStatusKind, AlertRule, ServerStatusPushPayload } from '@/types/api'
import type { ProcessSnapshot } from '@/types/metrics'
import { formatDateTime } from '@/utils/format'

const route = useRoute()
const metrics = useMetricsStore()
const serverId = Number(route.params.serverId)
const agentStatus = ref<AgentStatusKind>('offline')
const activeTab = ref<'chart' | 'table'>('chart')
/** 当前服务器的活跃告警规则（含全局规则），用于在图表上画阈值线。 */
const alertRules = ref<AlertRule[]>([])
/** 实时 Top 进程快照：REST 初载后由 metrics.update 捎带的 processes 节点覆盖。 */
const processSnapshot = ref<ProcessSnapshot | null>(null)
let latestHeartbeatAt: string | null = null
let socket: MonitorWebSocket | null = null

/** 时间选择器本地状态，与 store.timeRange 同步；选择变化时重新加载历史。 */
const timeRangeModel = ref<[Date, Date]>([...metrics.timeRange])

const timeShortcuts = [
  {
    text: '最近 1 小时',
    value: () => {
      const end = new Date()
      return [new Date(end.getTime() - 3600_000), end] as [Date, Date]
    }
  },
  {
    text: '最近 6 小时',
    value: () => {
      const end = new Date()
      return [new Date(end.getTime() - 6 * 3600_000), end] as [Date, Date]
    }
  },
  {
    text: '最近 24 小时',
    value: () => {
      const end = new Date()
      return [new Date(end.getTime() - 24 * 3600_000), end] as [Date, Date]
    }
  },
  {
    text: '最近 7 天',
    value: () => {
      const end = new Date()
      return [new Date(end.getTime() - 7 * 24 * 3600_000), end] as [Date, Date]
    }
  }
]

function onTimeRangeChange(range: [Date, Date] | null): void {
  if (!range) return
  void metrics.load(serverId, range[0], range[1])
}

const cards = computed(() => [
  { label: 'CPU', value: format(metrics.latest?.cpu_percent, '%') },
  { label: '内存', value: format(metrics.latest?.memory_percent, '%') },
  { label: '磁盘', value: format(metrics.latest?.disk_percent, '%') },
  { label: 'Load', value: format(metrics.latest?.load_avg, '') },
  { label: '网络接收', value: format(metrics.latest?.net_rx, ' B') },
  { label: '网络发送', value: format(metrics.latest?.net_tx, ' B') }
])

function format(value: number | null | undefined, suffix: string): string {
  return value === null || value === undefined ? '-' : `${value}${suffix}`
}

/** 仅应用当前服务器且心跳时间不早于当前快照的状态帧，防止延迟旧帧覆盖重连状态。 */
function applyServerStatus(payload: ServerStatusPushPayload): void {
  if (payload.server_id !== serverId) return
  if (payload.last_heartbeat_at !== null && latestHeartbeatAt !== null
    && Date.parse(payload.last_heartbeat_at) < Date.parse(latestHeartbeatAt)) {
    return
  }
  agentStatus.value = payload.agent_status
  latestHeartbeatAt = payload.last_heartbeat_at
}

async function loadServerStatus(): Promise<void> {
  try {
    const response = await getServerStatus(serverId)
    applyServerStatus(response.data)
  } catch {
    // 指标页状态快照失败不阻断指标加载或 Monitor WebSocket 建连。
  }
}

/** 拉取活跃告警规则（含全局规则）用于图表阈值线；失败不阻断图表。 */
async function loadAlertRules(): Promise<void> {
  try {
    const response = await listAlertRules()
    alertRules.value = response.data.filter((rule) => rule.enabled)
  } catch {
    // 阈值线为增强展示，加载失败时图表仍可用。
  }
}

/** 拉取实时进程快照作为 WS 推送前的初载数据；404/失败时保持空态。 */
async function loadProcessSnapshot(): Promise<void> {
  try {
    const response = await getLatestProcesses(serverId)
    processSnapshot.value = response.data
  } catch {
    // 无快照（Agent 未上报或版本过旧）时展示空态即可，不阻断页面。
  }
}

/** 应用 WS 推送的进程快照；仅在当前订阅服务器上应用。 */
function applyProcessSnapshot(snapshot: ProcessSnapshot): void {
  if (snapshot.server_id !== serverId) return
  processSnapshot.value = snapshot
}

onMounted(() => {
  timeRangeModel.value = [...metrics.timeRange]
  void metrics.load(serverId, timeRangeModel.value[0], timeRangeModel.value[1])
  void loadServerStatus()
  void loadAlertRules()
  void loadProcessSnapshot()
  socket = new MonitorWebSocket(metrics.applyRealtime, metrics.setConnected, undefined, undefined, undefined,
    applyServerStatus, applyProcessSnapshot)
  socket.connect(serverId)
})

onBeforeUnmount(() => {
  socket?.disconnect()
  socket = null
  metrics.reset()
})
</script>

<style scoped>
.metrics-view { max-width: 1200px; margin: 0 auto; }
.metric-cards { margin: 16px 0; }
.metric-label { color: var(--el-text-color-secondary); font-size: 13px; margin-bottom: 8px; }
.metric-cards strong { font-size: 20px; }
.history-card { margin-top: 16px; }
.history-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
</style>
