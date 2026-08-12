<template>
  <div class="dashboard-view">
    <DashboardHero
      :username="auth.user?.username"
      :role="auth.user?.role"
      :review-status="auth.user?.reviewStatus"
      :role-label="auth.user?.role ? userRoleLabel(auth.user.role) : ''"
      :refreshing="refreshing"
      :logging-out="loggingOut"
      @refresh="refresh"
      @logout="handleLogout"
    />

    <section aria-labelledby="availability-title">
      <div class="dashboard-view__section-heading">
        <div>
          <p>服务可用性</p>
          <h2 id="availability-title">
            系统现在是否正常运行
          </h2>
        </div>
      </div>
      <el-row
        :gutter="20"
        class="dashboard-view__cards dashboard-view__cards--availability"
      >
        <el-col
          :xs="24"
          :sm="12"
          :md="8"
        >
          <DashboardProbeCard
            title="健康检查"
            :ok="health.ok"
            :detail="health.detail"
            hint="后端应用标识"
            description="应用存活检查；不代表依赖已就绪。"
            :facts="[
              { label: '检查范围', value: '应用存活' },
              { label: '依赖范围', value: '不校验数据库与消息队列' }
            ]"
            :loading="loading.health"
            :pulse="true"
            ok-label="UP"
            :checked-at="health.checkedAt"
            :response-time-ms="health.responseTimeMs"
          />
        </el-col>
        <el-col
          :xs="24"
          :sm="12"
          :md="8"
        >
          <DashboardProbeCard
            title="就绪检查"
            :ok="ready.ok"
            :detail="ready.detail"
            hint="数据库健康状态"
            description="数据库校验；启用 RabbitMQ 时后端也校验 Broker。"
            :facts="[
              { label: '校验范围', value: '数据库连接与健康' },
              { label: 'Broker 校验', value: '启用 RabbitMQ 时纳入结果' }
            ]"
            :loading="loading.ready"
            ok-label="READY"
            :checked-at="ready.checkedAt"
            :response-time-ms="ready.responseTimeMs"
          />
        </el-col>
        <el-col
          :xs="24"
          :sm="12"
          :md="8"
        >
          <DashboardServersCard
            :count="servers.count"
            :data="sparkHistory"
            :loading="loading.servers"
            :online="serverStatuses.online"
            :offline="serverStatuses.offline"
            :unknown="serverStatuses.unknown"
            :sampled-count="serverStatuses.sampledCount"
            :complete="serverStatuses.complete"
            :server-name="selectedServer?.name ?? ''"
            :trend-samples="sparkHistory.length"
            :trend-collected-at="trendCollectedAt"
            :latest-cpu="latestMetrics.cpu"
            :latest-memory="latestMetrics.memory"
            :latest-disk="latestMetrics.disk"
            :latest-collected-at="latestMetrics.collectedAt"
          />
        </el-col>
      </el-row>
    </section>

    <section aria-labelledby="overview-title">
      <div class="dashboard-view__section-heading">
        <div>
          <p>运行概览</p>
          <h2 id="overview-title">
            服务器与告警状态
          </h2>
        </div>
      </div>
      <el-row
        :gutter="20"
        class="dashboard-view__cards"
      >
        <el-col
          :xs="24"
          :md="12"
        >
          <DashboardAlertSummaryCard
            :count="alerts.count"
            :highest-level="alerts.highestLevel"
            :loading="loading.alerts"
            @view-alerts="goAlertRecords"
          />
        </el-col>
        <el-col
          :xs="24"
          :md="12"
        >
          <DashboardSshCard
            :history="sshHistory"
            :loading="loading.sshHistory"
            :error="sshHistoryError"
          />
        </el-col>
      </el-row>
    </section>

    <section aria-labelledby="activity-title">
      <div class="dashboard-view__section-heading">
        <div>
          <p>近期活动</p>
          <h2 id="activity-title">
            等待处理的告警
          </h2>
        </div>
      </div>
      <DashboardRecentAlertsCard
        :alerts="alerts.items"
        :loading="loading.alerts"
        :error="alerts.error"
        @view-alerts="goAlertRecords"
      />
    </section>

    <section
      v-if="auth.isAdmin"
      class="dashboard-view__admin"
      aria-label="管理员快速入口"
    >
      <DashboardAdminCard
        :pending-count="pending.count"
        @review="goAdminUsers"
        @refresh="refresh"
      />
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiBusinessError } from '@/api/client'
import { listAlertRecords } from '@/api/alert'
import { getLatestMetrics, getMetricsHistory } from '@/api/metrics'
import { listServers, listSshTestHistory } from '@/api/server'
import { getHealth, getReady } from '@/api/system'
import { useAuthStore } from '@/stores/auth'
import { ErrorCode } from '@/types/error-code'
import { userRoleLabel } from '@/utils/format'
import DashboardAlertSummaryCard from '@/components/DashboardAlertSummaryCard.vue'
import DashboardAdminCard from '@/components/DashboardAdminCard.vue'
import DashboardHero from '@/components/DashboardHero.vue'
import DashboardProbeCard from '@/components/DashboardProbeCard.vue'
import DashboardRecentAlertsCard from '@/components/DashboardRecentAlertsCard.vue'
import DashboardServersCard from '@/components/DashboardServersCard.vue'
import DashboardSshCard from '@/components/DashboardSshCard.vue'
import type { AlertRecord, AlertLevel, Server, SshTestResult } from '@/types/api'

const SERVER_STATUS_SAMPLE_SIZE = 100
const RECENT_ALERTS_SIZE = 5

const router = useRouter()
const auth = useAuthStore()

interface ProbeResult { ok: boolean; detail: string; checkedAt: string | null; responseTimeMs: number | null }
interface CountedProbe extends ProbeResult { count: number }

const loading = reactive({ health: true, ready: true, servers: true, serverStatuses: true, alerts: true, pending: true, sshHistory: true })
const refreshing = ref(false)
const loggingOut = ref(false)
const health = ref<ProbeResult>({ ok: false, detail: '', checkedAt: null, responseTimeMs: null })
const ready = ref<ProbeResult>({ ok: false, detail: '', checkedAt: null, responseTimeMs: null })
const servers = ref<CountedProbe>({ ok: false, detail: '', checkedAt: null, responseTimeMs: null, count: 0 })
const pending = ref<CountedProbe>({ ok: false, detail: '', checkedAt: null, responseTimeMs: null, count: 0 })
const sparkHistory = ref<number[]>([])
const selectedServer = ref<Server | null>(null)
const trendCollectedAt = ref<string | null>(null)
const latestMetrics = reactive({ cpu: null as number | null, memory: null as number | null, disk: null as number | null, collectedAt: null as string | null })
const serverStatuses = reactive({ online: 0, offline: 0, unknown: 0, sampledCount: 0, complete: true })
const alerts = reactive<{ count: number; highestLevel: AlertLevel; items: AlertRecord[]; error: string }>({ count: 0, highestLevel: 'warning', items: [], error: '' })
const sshHistory = ref<SshTestResult[]>([])
const sshHistoryError = ref<string | null>(null)

function mapErrorToProbe(error: unknown, fallback: string): ProbeResult {
  if (error instanceof ApiBusinessError) {
    if (error.code === ErrorCode.UNAUTHORIZED) return { ok: false, detail: '需要登录', checkedAt: null, responseTimeMs: null }
    if (error.code === ErrorCode.FORBIDDEN) return { ok: false, detail: '无权访问', checkedAt: null, responseTimeMs: null }
    return { ok: false, detail: error.message || fallback, checkedAt: null, responseTimeMs: null }
  }
  return { ok: false, detail: fallback, checkedAt: null, responseTimeMs: null }
}

async function probeHealth(): Promise<void> {
  const startedAt = performance.now()
  try {
    const response = await getHealth()
    health.value = {
      ok: response.code === ErrorCode.SUCCESS,
      detail: response.data?.application ?? '-',
      checkedAt: response.data?.timestamp ?? null,
      responseTimeMs: Math.round(performance.now() - startedAt)
    }
  } catch (error) {
    health.value = mapErrorToProbe(error, '后端不可达')
  } finally { loading.health = false }
}

async function probeReady(): Promise<void> {
  const startedAt = performance.now()
  try {
    const response = await getReady()
    ready.value = {
      ok: response.code === ErrorCode.SUCCESS,
      detail: response.data?.database ?? '-',
      checkedAt: response.data?.timestamp ?? null,
      responseTimeMs: Math.round(performance.now() - startedAt)
    }
  } catch (error) {
    ready.value = mapErrorToProbe(error, '后端不可达')
  } finally { loading.ready = false }
}

async function probeServers(): Promise<Server | null> {
  try {
    const response = await listServers({ page: 1, page_size: SERVER_STATUS_SAMPLE_SIZE })
    const data = response.data
    const items = data?.items ?? []
    const total = data?.total ?? 0
    servers.value = { ok: response.code === ErrorCode.SUCCESS, detail: '见 /api/servers 列表', checkedAt: null, responseTimeMs: null, count: total }
    selectedServer.value = items[0] ?? null
    serverStatuses.online = items.filter((item) => item.status === 'online').length
    serverStatuses.offline = items.filter((item) => item.status === 'offline').length
    serverStatuses.unknown = items.filter((item) => item.status === 'unknown').length
    serverStatuses.sampledCount = items.length
    serverStatuses.complete = items.length >= total
    return items[0] ?? null
  } catch (error) {
    servers.value = { ...mapErrorToProbe(error, '需要登录'), count: 0 }
    selectedServer.value = null
    serverStatuses.online = 0
    serverStatuses.offline = 0
    serverStatuses.unknown = 0
    serverStatuses.sampledCount = 0
    serverStatuses.complete = true
    return null
  } finally {
    loading.servers = false
    loading.serverStatuses = false
  }
}

async function loadRecentAlerts(): Promise<void> {
  alerts.error = ''
  try {
    const response = await listAlertRecords({ page: 1, page_size: RECENT_ALERTS_SIZE, status: 'unread' })
    const data = response.data
    alerts.count = data?.total ?? 0
    alerts.items = data?.items ?? []
    alerts.highestLevel = alerts.items.some((item) => item.level === 'critical') ? 'critical' : 'warning'
  } catch (error) {
    alerts.count = 0
    alerts.items = []
    alerts.error = mapErrorToProbe(error, '未能加载近期告警').detail
  } finally { loading.alerts = false }
}

async function probePendingCount(): Promise<void> {
  if (!auth.isAdmin) {
    pending.value = { ok: false, detail: '非管理员', checkedAt: null, responseTimeMs: null, count: 0 }
    loading.pending = false
    return
  }
  try {
    const { listUsers } = await import('@/api/admin')
    const response = await listUsers({ status: 'pending', page: 1, page_size: 1 })
    pending.value = {
      ok: response.code === ErrorCode.SUCCESS,
      detail: '当前待审核用户数',
      checkedAt: null,
      responseTimeMs: null,
      count: response.data?.total ?? 0
    }
  } catch (error) {
    pending.value = { ...mapErrorToProbe(error, '加载失败'), count: 0 }
  } finally { loading.pending = false }
}

async function loadSparkHistory(server: Server | null): Promise<void> {
  sparkHistory.value = []
  trendCollectedAt.value = null
  if (!server) return
  try {
    const end = new Date()
    const start = new Date(end.getTime() - 7 * 24 * 60 * 60 * 1000)
    const response = await getMetricsHistory(server.id, start.toISOString(), end.toISOString(), 1, 100)
    const ordered = [...response.data.items].sort((a, b) => new Date(a.collected_at).getTime() - new Date(b.collected_at).getTime())
    sparkHistory.value = ordered.map((metric) => metric.cpu_percent).filter((value): value is number => value !== null)
    trendCollectedAt.value = ordered.at(-1)?.collected_at ?? null
  } catch {
    sparkHistory.value = []
    trendCollectedAt.value = null
  }
}

async function loadLatestMetrics(server: Server | null): Promise<void> {
  latestMetrics.cpu = null
  latestMetrics.memory = null
  latestMetrics.disk = null
  latestMetrics.collectedAt = null
  if (!server) return
  try {
    const response = await getLatestMetrics(server.id)
    const metric = response.data
    latestMetrics.cpu = metric?.cpu_percent ?? null
    latestMetrics.memory = metric?.memory_percent ?? null
    latestMetrics.disk = metric?.disk_percent ?? null
    latestMetrics.collectedAt = metric?.collected_at ?? null
  } catch {
    // 最新指标为辅助信息，失败不影响服务器总数、状态分布和历史趋势。
  }
}

async function loadSshHistory(server: Server | null): Promise<void> {
  sshHistory.value = []
  sshHistoryError.value = null
  if (!server) return
  try {
    const response = await listSshTestHistory(server.id)
    sshHistory.value = response.data ?? []
  } catch (error) {
    sshHistoryError.value = mapErrorToProbe(error, '未能加载 SSH 测试历史').detail
  } finally { loading.sshHistory = false }
}

async function refresh(): Promise<void> {
  if (refreshing.value) return
  refreshing.value = true
  loading.health = true
  loading.ready = true
  loading.servers = true
  loading.serverStatuses = true
  loading.alerts = true
  loading.pending = true
  loading.sshHistory = true
  const [,, firstServer] = await Promise.all([probeHealth(), probeReady(), probeServers(), loadRecentAlerts(), probePendingCount()])
  void Promise.all([loadSparkHistory(firstServer), loadLatestMetrics(firstServer), loadSshHistory(firstServer)])
  refreshing.value = false
}

function goAlertRecords(): void { void router.push({ name: 'alert-records' }) }
function goAdminUsers(): void { void router.push({ name: 'admin-users' }) }

async function handleLogout(): Promise<void> {
  if (loggingOut.value) return
  loggingOut.value = true
  try {
    await auth.logout()
    ElMessage.success('已退出登录')
    await router.push({ name: 'login' })
  } finally { loggingOut.value = false }
}

onMounted(() => { void refresh() })
</script>

<style>
.dashboard-view__card {
  background: rgba(255, 255, 255, 0.5) !important;
  backdrop-filter: blur(20px) saturate(180%);
  -webkit-backdrop-filter: blur(20px) saturate(180%);
  border: 1px solid rgba(255, 255, 255, 0.7) !important;
  border-radius: 16px;
  box-shadow: 0 12px 32px rgba(183, 50, 92, 0.12), inset 0 1px 0 rgba(255, 255, 255, 0.85);
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}
.dashboard-view__card:hover { transform: translateY(-2px); box-shadow: 0 18px 42px rgba(183, 50, 92, 0.16), inset 0 1px 0 rgba(255, 255, 255, 0.85); }
.dashboard-view__card .el-card__header { padding: 16px 20px; border-bottom: 1px solid rgba(183, 50, 92, 0.08); }
.dashboard-view__card .el-card__body { padding: 20px; }
.dashboard-view__card-header { display: flex; align-items: center; justify-content: space-between; }
.dashboard-view__card-title { display: flex; align-items: center; gap: 8px; color: #2a1626; font-size: 14px; font-weight: 600; }
.dashboard-view__badge { display: inline-block; padding: 4px 12px; border-radius: 12px; color: #fff; font-size: 12px; font-weight: 700; letter-spacing: 1px; white-space: nowrap; }
.dashboard-view__badge--ok { background: linear-gradient(135deg, #66e6a8 0%, #2eb872 100%); box-shadow: 0 2px 8px rgba(46, 184, 114, 0.35); }
.dashboard-view__badge--down { background: linear-gradient(135deg, #f86c6c 0%, #b7325c 100%); box-shadow: 0 2px 8px rgba(183, 50, 92, 0.35); }
.dashboard-view__badge--info { background: linear-gradient(135deg, #ff7aa3 0%, #b7325c 100%); box-shadow: 0 2px 8px rgba(255, 91, 138, 0.3); }
.dashboard-view__card-value { color: #2a1626; font-size: 20px; font-weight: 700; line-height: 1.4; overflow-wrap: anywhere; }
.dashboard-view__card-value--accent { color: #b7325c; font-size: 36px; font-variant-numeric: tabular-nums; }
.dashboard-view__card-hint { margin-top: 8px; color: #8a5872; font-size: 12px; letter-spacing: 0.5px; }
.dashboard-view__pulse { display: inline-block; width: 8px; height: 8px; background: #ff5b8a; border-radius: 50%; box-shadow: 0 0 0 0 rgba(255, 91, 138, 0.6); animation: dashboard-pulse 1.6s ease-out infinite; }
.dashboard-view__spark-wrap { margin-top: 12px; padding: 8px 4px 4px; border-top: 1px dashed rgba(183, 50, 92, 0.12); }
@keyframes dashboard-pulse { 0% { box-shadow: 0 0 0 0 rgba(255, 91, 138, 0.6); } 70% { box-shadow: 0 0 0 10px rgba(255, 91, 138, 0); } 100% { box-shadow: 0 0 0 0 rgba(255, 91, 138, 0); } }
</style>

<style scoped>
.dashboard-view { max-width: 1200px; margin: 0 auto; }
.dashboard-view section + section { margin-top: 28px; }
.dashboard-view__section-heading { display: flex; align-items: flex-end; justify-content: space-between; margin: 0 2px 12px; }
.dashboard-view__section-heading p { margin: 0; color: #b7325c; font-size: 12px; font-weight: 700; letter-spacing: 1px; }
h2 { margin: 4px 0 0; color: #2a1626; font-size: 20px; }
.dashboard-view__cards { margin-bottom: -20px; }
.dashboard-view__cards :deep(.el-col) { margin-bottom: 20px; }
.dashboard-view__cards--availability :deep(.el-col) { display: flex; }
.dashboard-view__cards--availability :deep(.dashboard-view__card) { width: 100%; height: 100%; }
.dashboard-view__overview-empty { display: flex; flex-direction: column; justify-content: center; min-height: 130px; }
.dashboard-view__overview-empty span { color: #b7325c; font-size: 12px; font-weight: 700; letter-spacing: 1px; }
.dashboard-view__overview-empty strong { margin-top: 8px; color: #2a1626; font-size: 16px; line-height: 1.5; }
.dashboard-view__overview-empty p { margin: 8px 0 0; color: #8a5872; font-size: 12px; line-height: 1.6; }
.dashboard-view__trend-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 22px; }
.dashboard-view__trend-header h3 { margin: 4px 0 0; color: #2a1626; font-size: 17px; }
.dashboard-view__trend-header span { padding: 4px 8px; color: #8a5872; background: rgba(255, 91, 138, 0.1); border-radius: 8px; font-size: 11px; }
.dashboard-view__trend-hint { margin: 14px 0 0; color: #8a5872; font-size: 12px; line-height: 1.6; }
.dashboard-view__admin { max-width: 590px; }
@media (max-width: 640px) { h2 { font-size: 18px; } .dashboard-view section + section { margin-top: 22px; } }
</style>
