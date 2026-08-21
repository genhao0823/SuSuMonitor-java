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
          <el-icon aria-hidden="true">
            <Plus />
          </el-icon>
          <span>创建服务器</span>
        </el-button>
      </template>
    </PageHeader>

    <el-card
      class="server-list-view__card"
      shadow="never"
    >
      <ServerSearchBar
        v-model:keyword="keyword"
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
          width="110"
        >
          <template #default="{ row }">
            {{ row.ssh_user }}@{{ row.ssh_host }}:{{ row.ssh_port }}
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
          width="360"
          fixed="right"
        >
          <template #default="{ row }">
            <div class="table-action-group">
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
            </div>
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
import { Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { ApiBusinessError } from '@/api/client'
import { getMetricsHistory } from '@/api/metrics'
import { deleteServer, listServers, testSshConnection } from '@/api/server'
import { useDebouncedRef } from '@/composables/useDebouncedRef'
import PageHeader from '@/components/PageHeader.vue'
import ServerFormDialog from '@/components/ServerFormDialog.vue'
import ServerPagination from '@/components/ServerPagination.vue'
import ServerSearchBar from '@/components/ServerSearchBar.vue'
import ServerSparkLine from '@/components/ServerSparkLine.vue'
import { useAuthStore } from '@/stores/auth'
import { ErrorCode } from '@/types/error-code'
import { formatDateTime, serverStatusLabel } from '@/utils/format'
import type { Server, ServerQuery } from '@/types/api'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const loading = ref(false)
const serverItems = ref<Server[]>([])
const totalCount = ref<number>(0)
const page = ref(1)
const pageSizeOptions: number[] = [10, 20, 50]
const pageSize = ref<number>(pageSizeOptions[0])
const keyword = useDebouncedRef<string>(
  typeof route.query.keyword === 'string' ? route.query.keyword : '',
  500
)
const sortBy = ref<ServerQuery['sort_by']>('id')
const sortOrder = ref<ServerQuery['sort_order']>('desc')

const dialogVisible = ref(false)
const editingServer = ref<Server | null>(null)
const sparkHistories = ref<Map<number, number[]>>(new Map())
const STATUS_REFRESH_INTERVAL_MS = 30_000
let statusRefreshTimer: ReturnType<typeof setInterval> | null = null

function buildQuery(): ServerQuery {
  const query: ServerQuery = {
    page: page.value,
    page_size: pageSize.value,
    sort_by: sortBy.value,
    sort_order: sortOrder.value
  }
  const normalizedKeyword = keyword.value.trim()
  if (normalizedKeyword.length > 0) {
    query.keyword = normalizedKeyword
  }
  return query
}

async function fetchList(): Promise<void> {
  loading.value = true
  try {
    const response = await listServers(buildQuery())
    serverItems.value = response.data.items
    totalCount.value = response.data.total
  } finally {
    loading.value = false
  }
}

async function reload(): Promise<void> {
  try {
    await fetchList()
    void loadAllSparkHistories()
    syncQueryToUrl()
  } catch (error) {
    ElMessage.error(explainError(error))
  }
}

function startStatusRefresh(): void {
  statusRefreshTimer = setInterval(() => {
    void fetchList().catch(() => {
      // 周期刷新失败时保留当前列表，避免反复弹出错误提示。
    })
  }, STATUS_REFRESH_INTERVAL_MS)
}

function onReload(): void {
  page.value = 1
  void reload()
}

function syncQueryToUrl(): void {
  const query: Record<string, string> = {}
  const normalizedKeyword = keyword.value.trim()
  if (normalizedKeyword.length > 0) query.keyword = normalizedKeyword
  if (page.value !== 1) query.page = String(page.value)
  if (pageSize.value !== pageSizeOptions[0]) query.page_size = String(pageSize.value)
  if (sortBy.value !== 'id') query.sort_by = String(sortBy.value)
  if (sortOrder.value !== 'desc') query.sort_order = String(sortOrder.value)
  void router.replace({ name: 'servers', query })
}

function restoreQueryFromUrl(): void {
  const query = route.query
  if (typeof query.page === 'string') {
    const nextPage = Number.parseInt(query.page, 10)
    if (!Number.isNaN(nextPage) && nextPage >= 1) page.value = nextPage
  }
  if (typeof query.page_size === 'string') {
    const nextPageSize = Number.parseInt(query.page_size, 10)
    if (pageSizeOptions.includes(nextPageSize)) pageSize.value = nextPageSize
  }
  if (typeof query.sort_by === 'string') {
    const allowedSortFields = ['id', 'name', 'host', 'created_at', 'updated_at'] as const
    if ((allowedSortFields as readonly string[]).includes(query.sort_by)) {
      sortBy.value = query.sort_by as ServerQuery['sort_by']
    }
  }
  if (query.sort_order === 'asc' || query.sort_order === 'desc') {
    sortOrder.value = query.sort_order
  }
}

function onSortChange(sort: {
  prop: string | null
  order: 'ascending' | 'descending' | null
}): void {
  if (sort.order === null || sort.prop === null) {
    sortBy.value = 'id'
    sortOrder.value = 'desc'
  } else {
    sortBy.value = sort.prop as ServerQuery['sort_by']
    sortOrder.value = sort.order === 'ascending' ? 'asc' : 'desc'
  }
  page.value = 1
  void reload()
}

function onPageSizeChange(size: number): void {
  pageSize.value = size
  page.value = 1
  void reload()
}

function onPageChange(nextPage: number): void {
  if (nextPage === page.value) return
  page.value = nextPage
  void reload()
}

function cpuHistory(serverId: number): number[] {
  return sparkHistories.value.get(serverId) ?? []
}

async function loadAllSparkHistories(): Promise<void> {
  const ids = serverItems.value.map((server) => server.id)
  if (ids.length === 0) {
    sparkHistories.value = new Map()
    return
  }
  const end = new Date()
  const start = new Date(end.getTime() - 7 * 24 * 60 * 60 * 1000)
  const results = await Promise.allSettled(
    ids.map((id) => getMetricsHistory(id, start.toISOString(), end.toISOString(), 1, 100))
  )
  const nextHistories = new Map<number, number[]>()
  ids.forEach((id, index) => {
    const result = results[index]
    if (result.status !== 'fulfilled') return
    const cpuSeries = [...result.value.data.items]
      .sort(
        (left, right) =>
          new Date(left.collected_at).getTime() - new Date(right.collected_at).getTime()
      )
      .map((metrics) => metrics.cpu_percent)
      .filter((value): value is number => value !== null)
    nextHistories.set(id, cpuSeries)
  })
  sparkHistories.value = nextHistories
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

function handleTestConnection(row: Server): void {
  void testSshConnection(row.id)
    .then((response) => {
      const result = response.data
      if (result.connected) {
        ElMessage.success(
          `SSH 连接成功 (${result.duration_ms}ms) · 认证方式 ${result.auth_type}`
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

function handleOpenTerminal(row: Server): void {
  void router.push({ name: 'terminal', params: { serverId: row.id } })
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
    if (error.code === ErrorCode.RESOURCE_NOT_FOUND) return '服务器不存在或已被删除'
    if (error.code === ErrorCode.FORBIDDEN) return '当前账号无权操作'
    return error.message || '操作失败'
  }
  return '网络异常,请稍后重试'
}

const initialized = ref(false)

onMounted(() => {
  restoreQueryFromUrl()
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

watch(keyword, () => {
  if (!initialized.value) return
  page.value = 1
  void reload()
})
</script>

<style scoped>
.server-list-view__card {
  margin-top: 16px;
}

.server-list-view__create {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.server-list-view__create :deep(.el-icon) {
  width: 16px;
  height: 16px;
}

.server-list-view__name-link {
  color: var(--susu-primary-deep, #b7325c);
  font-weight: 600;
}

.server-list-view__name-link:hover {
  color: var(--susu-primary, #ff5b8a);
  text-decoration: underline;
}

.server-list-view__status {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 600;
}

.server-list-view__status--online {
  color: #15803d;
  background: rgba(34, 197, 94, 0.12);
}

.server-list-view__status--offline {
  color: #be123c;
  background: rgba(244, 63, 94, 0.12);
}

.server-list-view__status--unknown {
  color: #b45309;
  background: rgba(245, 158, 11, 0.14);
}

.server-list-view__agent {
  font-size: 12px;
  color: #64748b;
}

.server-list-view__agent--online {
  color: #15803d;
  font-weight: 600;
}

.server-list-view__agent--offline {
  color: #be123c;
}
</style>
