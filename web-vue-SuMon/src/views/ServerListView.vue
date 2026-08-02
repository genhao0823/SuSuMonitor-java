<template>
  <div class="server-list-view">
    <PageHeader
      title="服务器列表"
      subtitle="管理已注册的服务器资产,只有管理员可以增删改,普通用户可以查看"
    >
      <template #actions>
        <el-button
          v-if="auth.isAdmin"
          type="primary"
          class="server-list-view__create"
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
          <span>创建服务器</span>
        </el-button>
      </template>
    </PageHeader>

    <el-card
      class="server-list-view__card"
      shadow="never"
    >
      <ServerSearchBar
        v-model:name-value="searchName"
        v-model:host-value="searchHost"
        v-model:page-size="pageSize"
        :page-size-options="pageSizeOptions"
        @reload="onReload"
      />

      <el-table
        v-loading="loading"
        :data="serverItems"
        stripe
        class="server-list-view__table"
        empty-text="暂无服务器,点击右上角创建"
        @sort-change="onSortChange"
      >
        <el-table-column
          prop="id"
          label="ID"
          width="80"
          sortable="custom"
        />
        <el-table-column
          prop="name"
          label="名称"
          min-width="160"
        >
          <template #default="{ row }">
            <router-link
              :to="{ name: 'server-detail', params: { serverId: row.id } }"
              class="server-list-view__name-link"
            >
              {{ row.name }}
            </router-link>
          </template>
        </el-table-column>
        <el-table-column
          prop="host"
          label="主机"
          min-width="180"
          show-overflow-tooltip
        />
        <el-table-column
          label="状态"
          width="110"
        >
          <template #default="{ row }">
            <span
              class="server-list-view__status"
              :class="`server-list-view__status--${row.status}`"
            >
              {{ serverStatusLabel(row.status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column
          label="Agent"
          width="110"
        >
          <template #default="{ row }">
            <span
              class="server-list-view__agent"
              :class="`server-list-view__agent--${row.agent_status}`"
            >
              {{ row.agent_status }}
            </span>
          </template>
        </el-table-column>
        <el-table-column
          prop="ssh_port"
          label="SSH"
          width="90"
        >
          <template #default="{ row }">
            {{ row.ssh_user }}@:{{ row.ssh_port }}
          </template>
        </el-table-column>
        <el-table-column
          label="CPU 趋势"
          width="140"
        >
          <template #default="{ row }">
            <ServerSparkLine
              :data="cpuHistory(row.id)"
              label="7d"
            />
          </template>
        </el-table-column>
        <el-table-column
          prop="created_at"
          label="创建时间"
          width="180"
          sortable="custom"
        >
          <template #default="{ row }">
            {{ formatDateTime(row.created_at) }}
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="320"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button
              size="small"
              @click="goDetail(row as Server)"
            >
              详情
            </el-button>
            <el-button
              v-if="auth.isAdmin"
              size="small"
              type="primary"
              plain
              @click="openEdit(row as Server)"
            >
              编辑
            </el-button>
            <el-button
              v-if="auth.isAdmin"
              size="small"
              plain
              @click="handleTestConnection(row as Server)"
            >
              测试连接
            </el-button>
            <el-button
              v-if="auth.isApproved"
              size="small"
              plain
              type="primary"
              @click="handleOpenTerminal(row as Server)"
            >
              打开终端
            </el-button>
            <el-popconfirm
              v-if="auth.isAdmin"
              :title="`确定要删除 ${(row as Server).name} 吗?`"
              confirm-button-text="删除"
              cancel-button-text="取消"
              @confirm="handleDelete(row as Server)"
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

      <ServerPagination
        :page="page"
        :page-size="pageSize"
        :total="totalCount"
        :page-size-options="pageSizeOptions"
        @update:page="onPageChange"
        @update:page-size="onPageSizeChange"
      />
    </el-card>

    <ServerFormDialog
      v-model="dialogVisible"
      :server="editingServer"
      @success="reload"
    />
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import ServerFormDialog from '@/components/ServerFormDialog.vue'
import ServerSearchBar from '@/components/ServerSearchBar.vue'
import ServerPagination from '@/components/ServerPagination.vue'
import ServerSparkLine from '@/components/ServerSparkLine.vue'
import { ApiBusinessError } from '@/api/client'
import { deleteServer, listServers, testSshConnection as testSsh } from '@/api/server'
import { getMetricsHistory } from '@/api/metrics'
import { useAuthStore } from '@/stores/auth'
import { useDebouncedRef } from '@/composables/useDebouncedRef'
import { ErrorCode } from '@/types/error-code'
import type { Server, ServerQuery, SshTestResult } from '@/types/api'
import { formatDateTime, serverStatusLabel } from '@/utils/format'

const auth = useAuthStore()

const router = useRouter()
const route = useRoute()

const loading = ref(false)
const serverItems = ref<Server[]>([])

const page = ref(1)
const pageSizeOptions: number[] = [10, 20, 50]
const pageSize = ref<number>(pageSizeOptions[0])
/**
 * 搜索关键字使用 useDebouncedRef,500ms 内连续输入只触发最后一次 reload。
 * 直接绑到 ServerSearchBar 的 v-model,无需再写中间 emit 监听。
 */
const searchName = useDebouncedRef<string>('', 500)
const searchHost = useDebouncedRef<string>('', 500)
const sortBy = ref<ServerQuery['sort_by']>('id')
const sortOrder = ref<ServerQuery['sort_order']>('desc')

const dialogVisible = ref(false)
const editingServer = ref<Server | null>(null)

function buildQuery(): ServerQuery {
  const q: ServerQuery = {
    page: page.value,
    page_size: pageSize.value,
    sort_by: sortBy.value,
    sort_order: sortOrder.value
  }
  const name = searchName.value.trim()
  const host = searchHost.value.trim()
  if (name.length > 0) {
    q.name = name
  }
  if (host.length > 0) {
    q.host = host
  }
  return q
}

const totalCount = ref<number>(0)
const STATUS_REFRESH_INTERVAL_MS = 30_000
let statusRefreshTimer: ReturnType<typeof setInterval> | null = null

async function fetchList(): Promise<void> {
  loading.value = true
  try {
    const response = await listServers(buildQuery())
    serverItems.value = response.data?.items ?? []
    totalCount.value = response.data?.total ?? 0
  } finally {
    loading.value = false
  }
}

async function reload(): Promise<void> {
  try {
    await fetchList()
    // spark line 异步拉取,失败不影响列表展示
    void loadAllSparkHistories()
    syncQueryToUrl()
  } catch (error) {
    ElMessage.error(explainError(error))
  }
}

/** 每 30 秒刷新列表状态，不重复拉取指标趋势或修改 URL。 */
function startStatusRefresh(): void {
  statusRefreshTimer = setInterval(() => {
    void fetchList().catch(() => {
      // 自动刷新失败时保留当前数据，避免周期性弹出错误提示。
    })
  }, STATUS_REFRESH_INTERVAL_MS)
}

/**
 * 工具条"刷新" / 回车 / 切换 pageSize 的统一入口:
 * 始终重置 page=1 再 reload,避免新关键字被旧 page 切片跳过。
 */
function onReload(): void {
  page.value = 1
  void reload()
}

/**
 * 把当前筛选条件同步到 URL query(供 F5 / 分享链接恢复)。
 * 排除默认值,保持 URL 干净;router.replace 不留历史。
 */
function syncQueryToUrl(): void {
  const q: Record<string, string> = {}
  const name = searchName.value.trim()
  const host = searchHost.value.trim()
  if (name.length > 0) q.name = name
  if (host.length > 0) q.host = host
  if (page.value !== 1) q.page = String(page.value)
  if (pageSize.value !== pageSizeOptions[0]) q.page_size = String(pageSize.value)
  if (sortBy.value !== 'id') q.sort_by = String(sortBy.value)
  if (sortOrder.value !== 'desc') q.sort_order = String(sortOrder.value)
  void router.replace({ name: 'servers', query: q })
}

/**
 * 从 URL query 恢复筛选条件。仅在 onMounted 调用一次,避免双触发 reload。
 */
function restoreQueryFromUrl(): void {
  const q = route.query
  if (typeof q.name === 'string') searchName.value = q.name
  if (typeof q.host === 'string') searchHost.value = q.host
  if (typeof q.page === 'string') {
    const n = Number.parseInt(q.page, 10)
    if (!Number.isNaN(n) && n >= 1) page.value = n
  }
  if (typeof q.page_size === 'string') {
    const n = Number.parseInt(q.page_size, 10)
    if (pageSizeOptions.includes(n)) pageSize.value = n
  }
  if (typeof q.sort_by === 'string') {
    const allowed = ['id', 'name', 'host', 'created_at', 'updated_at'] as const
    if ((allowed as readonly string[]).includes(q.sort_by)) {
      sortBy.value = q.sort_by as ServerQuery['sort_by']
    }
  }
  if (q.sort_order === 'asc' || q.sort_order === 'desc') {
    sortOrder.value = q.sort_order
  }
}

function onSortChange(sort: { prop: string | null; order: 'ascending' | 'descending' | null }): void {
  if (sort.order === null || sort.prop === null) {
    sortBy.value = 'id'
    sortOrder.value = 'desc'
  } else {
    sortBy.value = sort.prop as ServerQuery['sort_by']
    sortOrder.value = sort.order === 'ascending' ? 'asc' : 'desc'
  }
  page.value = 1
  // 后端排序由 buildQuery() 拼入 sort_by / sort_order,这里直接重拉。
  void reload()
}

function onPageSizeChange(size: number): void {
  pageSize.value = size
  page.value = 1
  void reload()
}

/**
 * 切换页码:仅刷新 page.value,触发 reload 拉新数据。
 * buildQuery() 已读 page.value,无需重置其它状态。
 */
function onPageChange(nextPage: number): void {
  if (nextPage === page.value) {
    return
  }
  page.value = nextPage
  void reload()
}

/**
 * spark line 数据缓存:Map<serverId, cpu_percent 序列>。
 * 由 loadAllSparkHistories 在 reload 完成后异步填充。
 */
const sparkHistories = ref<Map<number, number[]>>(new Map())

/**
 * 给指定 serverId 返回其 CPU 趋势序列(供模板用)。
 * 数据不足或尚未拉取时返回空数组,组件会显示空态。
 */
function cpuHistory(serverId: number): number[] {
  return sparkHistories.value.get(serverId) ?? []
}

/**
 * 拉取当前列表所有 server 的最近 7 天 metrics 历史(仅 cpu_percent)。
 * 单个失败不影响其他,Promise.allSettled 兜底。
 */
async function loadAllSparkHistories(): Promise<void> {
  const ids = serverItems.value.map((s) => s.id)
  if (ids.length === 0) {
    sparkHistories.value = new Map()
    return
  }
  const end = new Date()
  const start = new Date(end.getTime() - 7 * 24 * 60 * 60 * 1000)
  const startISO = start.toISOString()
  const endISO = end.toISOString()
  const next = new Map<number, number[]>()
  const results = await Promise.allSettled(
    ids.map((id) =>
      getMetricsHistory(id, startISO, endISO, 1, 100)
    )
  )
  ids.forEach((id, i) => {
    const r = results[i]
    if (r.status === 'fulfilled') {
      const cpuSeries = r.value.data.items
        .map((m) => m.cpu_percent)
        .filter((v): v is number => v !== null)
      next.set(id, cpuSeries)
    }
  })
  sparkHistories.value = next
}

function openCreate(): void {
  editingServer.value = null
  dialogVisible.value = true
}

function openEdit(row: Server): void {
  editingServer.value = row
  dialogVisible.value = true
}

function goDetail(row: Server): void {
  void router.push({ name: 'server-detail', params: { serverId: row.id } })
}

/** 打开 Web 终端视图(MVP-7 T4)。仅 approved 用户显示按钮,后端再次校验权限。 */
function handleOpenTerminal(row: Server): void {
  void router.push({ name: 'terminal', params: { serverId: row.id } })
}

function handleTestConnection(row: Server): void {
  void testSsh(row.id)
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

async function handleDelete(row: Server): Promise<void> {
  try {
    await deleteServer(row.id)
    ElMessage.success(`已删除 ${row.name}`)
    if (serverItems.value.length === 1 && page.value > 1) {
      page.value -= 1
    }
    await reload()
  } catch (error) {
    ElMessage.error(explainError(error))
  }
}

function explainError(error: unknown): string {
  if (error instanceof ApiBusinessError) {
    if (error.code === ErrorCode.RESOURCE_NOT_FOUND) {
      return '服务器不存在或已被删除'
    }
    if (error.code === ErrorCode.FORBIDDEN) {
      return '当前账号无权操作'
    }
    return error.message || '操作失败'
  }
  return '网络异常,请稍后重试'
}

/** 抑制 watch 触发标志;restoreQueryFromUrl 写 searchName 时置 true,绕开 watch */
let suppressWatch = true

/** 首次加载完成的标志位,避免 watch 误触双跑 reload */
const initialized = ref(false)

onMounted(() => {
  // 先从 URL 恢复筛选条件(供 F5 / 分享链接恢复),再 reload
  restoreQueryFromUrl()
  suppressWatch = false
  void reload().finally(() => {
    initialized.value = true
    startStatusRefresh()
  })
})

onBeforeUnmount(() => {
  if (statusRefreshTimer !== null) {
    clearInterval(statusRefreshTimer)
    statusRefreshTimer = null
  }
})

/**
 * 监听 debounced 搜索关键字:输入停顿 500ms 后自动重拉列表,
 * 同时重置 page=1。useDebouncedRef 已合并连续写入,这里只看最终值。
 * 仅在 initialized=true 后触发,跳过 onMounted 的首次 reload。
 */
watch([searchName, searchHost], () => {
  if (suppressWatch || !initialized.value) return
  page.value = 1
  void reload()
})
</script>

<style scoped>
.server-list-view {
  max-width: 1280px;
  margin: 0 auto;
}

.server-list-view__card {
  background: rgba(255, 255, 255, 0.5);
  backdrop-filter: blur(20px) saturate(180%);
  -webkit-backdrop-filter: blur(20px) saturate(180%);
  border: 1px solid rgba(255, 255, 255, 0.7);
  border-radius: 16px;
  box-shadow:
    0 12px 32px rgba(183, 50, 92, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.85);
}

.server-list-view__card :deep(.el-card__body) {
  padding: 20px;
}

.server-list-view__create {
  background: linear-gradient(135deg, #ff5b8a 0%, #b7325c 100%) !important;
  border: none !important;
  box-shadow: 0 6px 14px rgba(255, 91, 138, 0.3) !important;
}

.server-list-view__create:hover {
  background: linear-gradient(135deg, #ff7aa3 0%, #c8426f 100%) !important;
  box-shadow: 0 10px 20px rgba(255, 91, 138, 0.45) !important;
}

.server-list-view__create svg {
  width: 16px;
  height: 16px;
  margin-right: 4px;
  vertical-align: -2px;
}

.server-list-view__table {
  border-radius: 8px;
  overflow: hidden;
}

.server-list-view__name-link {
  color: #b7325c;
  text-decoration: none;
  font-weight: 600;
}

.server-list-view__name-link:hover {
  color: #ff5b8a;
  text-decoration: underline;
}

.server-list-view__status,
.server-list-view__agent {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.5px;
  color: #fff;
}

.server-list-view__status--online,
.server-list-view__agent--online {
  background: linear-gradient(135deg, #66e6a8 0%, #2eb872 100%);
}

.server-list-view__status--offline,
.server-list-view__agent--offline {
  background: linear-gradient(135deg, #9ca3af 0%, #6b7280 100%);
}

.server-list-view__status--unknown,
.server-list-view__agent--unknown {
  background: linear-gradient(135deg, #f5b942 0%, #d97706 100%);
}
</style>