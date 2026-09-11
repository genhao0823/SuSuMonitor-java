<template>
  <div class="admin-users-view">
    <PageHeader
      title="用户审核"
      subtitle="处理待审核用户的注册申请;通过或拒绝用户注册请求"
    >
      <template #actions>
        <el-button
          :loading="refreshing"
          class="admin-users-view__refresh"
          aria-label="刷新待审核列表"
          @click="reload"
        >
          <svg
            viewBox="0 0 24 24"
            xmlns="http://www.w3.org/2000/svg"
            aria-hidden="true"
          >
            <path
              d="M4 12 A8 8 0 0 1 18 7"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
            />
            <path
              d="M18 4 L18 8 L14 8"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
            <path
              d="M20 12 A8 8 0 0 1 6 17"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
            />
            <path
              d="M6 20 L6 16 L10 16"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
          <span>刷新</span>
        </el-button>
      </template>
    </PageHeader>

    <el-card
      class="admin-users-view__card"
      shadow="never"
    >
      <div class="admin-users-view__summary">
        <el-tabs
          v-model="activeStatus"
          class="admin-users-view__tabs"
          @tab-change="onTabChange"
        >
          <el-tab-pane
            label="待审核"
            name="pending"
          />
          <el-tab-pane
            label="已通过"
            name="approved"
          />
          <el-tab-pane
            label="已拒绝"
            name="rejected"
          />
        </el-tabs>
        <el-tag
          :type="pendingTotal > 0 ? 'warning' : 'info'"
          effect="dark"
          size="default"
        >
          {{ pendingTotal }} 人
        </el-tag>
        <el-input
          v-model="searchKeyword"
          placeholder="按用户名搜索(回车或清除后确认)"
          clearable
          class="admin-users-view__search"
          @keyup.enter="onSearch"
          @clear="onSearch"
        />
        <div
          v-if="activeStatus === 'pending'"
          class="admin-users-view__batch"
        >
          <el-button
            size="small"
            type="success"
            :disabled="selectedIds.length === 0 || batchBusy"
            @click="batchApprove"
          >
            批量通过{{ selectedIds.length > 0 ? `(${selectedIds.length})` : '' }}
          </el-button>
          <el-popconfirm
            :title="`确定批量拒绝所选 ${selectedIds.length} 个用户吗?`"
            confirm-button-text="拒绝"
            cancel-button-text="取消"
            :disabled="selectedIds.length === 0 || batchBusy"
            @confirm="batchReject"
          >
            <template #reference>
              <el-button
                size="small"
                type="danger"
                plain
                :disabled="selectedIds.length === 0 || batchBusy"
              >
                批量拒绝{{ selectedIds.length > 0 ? `(${selectedIds.length})` : '' }}
              </el-button>
            </template>
          </el-popconfirm>
        </div>
      </div>

      <el-table
        ref="tableRef"
        v-loading="loading"
        :data="pendingList"
        stripe
        class="admin-users-view__table"
        :empty-text="searchKeyword.trim().length > 0 ? '无匹配用户' : emptyTextByStatus"
        @selection-change="onSelectionChange"
      >
        <el-table-column
          v-if="activeStatus === 'pending'"
          type="selection"
          width="48"
        />
        <el-table-column
          prop="id"
          label="ID"
          width="80"
        />
        <el-table-column
          label="用户名"
          min-width="200"
        >
          <template #default="{ row }">
            <strong>{{ (row as CurrentUser).username }}</strong>
          </template>
        </el-table-column>
        <el-table-column
          label="注册时间"
          width="220"
        >
          <template #default="{ row }">
            {{ formatDateTime((row as CurrentUser).createdAt) }}
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="280"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button
              size="small"
              type="success"
              :loading="busyId === row.id && busyAction === 'approve'"
              :disabled="busyId !== null && busyId !== row.id"
              @click="approve(row as CurrentUser)"
            >
              通过
            </el-button>
            <el-popconfirm
              :title="`确定拒绝 ${(row as CurrentUser).username} 吗?`"
              confirm-button-text="拒绝"
              cancel-button-text="取消"
              @confirm="reject(row as CurrentUser)"
            >
              <template #reference>
                <el-button
                  size="small"
                  type="danger"
                  plain
                  :loading="busyId === row.id && busyAction === 'reject'"
                  :disabled="busyId !== null && busyId !== row.id"
                >
                  拒绝
                </el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>

      <div class="admin-users-view__pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="pendingTotal"
          :page-sizes="pageSizeOptions"
          layout="total, sizes, prev, pager, next"
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import { ApiBusinessError } from '@/api/client'
import {
  approveUser,
  batchApproveUsers,
  batchRejectUsers,
  listUsers,
  rejectUser
} from '@/api/admin'
import { ErrorCode } from '@/types/error-code'
import type { CurrentUser } from '@/types/api'
import { formatDateTime } from '@/utils/format'

const loading = ref(false)
const refreshing = ref(false)
/**
 * 当前正在处理的行 id + 动作,用于按钮级 loading。
 * busyId 非空时,其他行的操作按钮被禁用,防止并发审核混乱。
 */
const busyId = ref<number | null>(null)
const busyAction = ref<'approve' | 'reject' | null>(null)
/** 批量操作进行中(禁用全部批量按钮防止双击)。 */
const batchBusy = ref(false)

const pendingList = ref<CurrentUser[]>([])
const pendingTotal = ref(0)

// 分页状态,与 OpenAPI listUsers 参数对齐。
const page = ref(1)
const pageSizeOptions = [20, 50, 100]
const pageSize = ref<number>(pageSizeOptions[0])
/** 用户名搜索关键字(回车/清除时触发远端查询)。 */
const searchKeyword = ref('')
/** 当前审核状态 tab:待审核/已通过/已拒绝。 */
const activeStatus = ref<'pending' | 'approved' | 'rejected'>('pending')

/** 空态文案按状态区分。 */
const emptyTextByStatus = computed<string>(() => {
  if (activeStatus.value === 'pending') {
    return '暂无待审核用户,所有申请已处理完毕'
  }
  return activeStatus.value === 'approved' ? '暂无已通过用户' : '暂无已拒绝用户'
})

// el-table selection 列状态。
const tableRef = ref<{ clearSelection: () => void } | null>(null)
const selectedIds = ref<number[]>([])

/**
 * 拉取用户分页列表(远端状态筛选 + 关键字搜索)。
 */
async function fetchPending(): Promise<void> {
  loading.value = true
  try {
    const response = await listUsers({
      status: activeStatus.value,
      page: page.value,
      page_size: pageSize.value,
      keyword: searchKeyword.value.trim()
    })
    pendingList.value = response.data?.items ?? []
    pendingTotal.value = response.data?.total ?? 0
  } finally {
    loading.value = false
  }
}

/** 切换状态 tab:重置到第 1 页并重新查询。 */
function onTabChange(): void {
  page.value = 1
  clearSelection()
  void fetchPending().catch((error) => ElMessage.error(explainError(error)))
}

/** 搜索触发:重置到第 1 页并重新查询。 */
function onSearch(): void {
  page.value = 1
  void fetchPending().catch((error) => ElMessage.error(explainError(error)))
}

/** 顶层刷新按钮。 */
async function reload(): Promise<void> {
  if (refreshing.value) {
    return
  }
  refreshing.value = true
  try {
    await fetchPending()
  } catch (error) {
    ElMessage.error(explainError(error))
  } finally {
    refreshing.value = false
  }
}

/** 翻页/改每页大小后重新拉取,并清空勾选避免越页残留。 */
function onPageChange(targetPage?: number): void {
  if (targetPage !== undefined) {
    page.value = targetPage
  }
  clearSelection()
  void fetchPending().catch((error) => ElMessage.error(explainError(error)))
}
function onSizeChange(targetSize?: number): void {
  if (targetSize !== undefined) {
    pageSize.value = targetSize
  }
  page.value = 1
  clearSelection()
  void fetchPending().catch((error) => ElMessage.error(explainError(error)))
}

/** el-table 勾选变化时同步 selectedIds。 */
function onSelectionChange(rows: unknown[]): void {
  selectedIds.value = rows.map((row) => (row as CurrentUser).id)
}

function clearSelection(): void {
  // el-table 实例在 jsdom/测试环境下可能缺省 clearSelection,防御性调用。
  tableRef.value?.clearSelection?.()
}

/** 批量通过:后端逐 id 原子审核,结果展示 processed/failed。 */
async function batchApprove(): Promise<void> {
  if (selectedIds.value.length === 0) {
    return
  }
  await runBatch(batchApproveUsers, '通过')
}

/** 批量拒绝(el-popconfirm 已二次确认)。 */
async function batchReject(): Promise<void> {
  if (selectedIds.value.length === 0) {
    return
  }
  await runBatch(batchRejectUsers, '拒绝')
}

async function runBatch(
  action: (ids: number[]) => Promise<{ data: { processed: number; failed: number; failed_ids?: number[] } }>,
  label: string
): Promise<void> {
  if (batchBusy.value) {
    return
  }
  batchBusy.value = true
  const ids = [...selectedIds.value]
  try {
    const response = await action(ids)
    const { processed, failed, failed_ids } = response.data
    if (failed === 0) {
      ElMessage.success(`已批量${label} ${processed} 个用户`)
    } else {
      ElMessage.warning(`批量${label}完成:成功 ${processed} 个,失败 ${failed} 个(可能已被处理)`)
      await showFailedDetail(failed_ids ?? [], label)
    }
    clearSelection()
    await fetchPending()
  } catch (error) {
    ElMessage.error(explainError(error))
  } finally {
    batchBusy.value = false
  }
}

/** 批量失败明细弹窗:failed_ids 映射当前列表用户名,映射不到的显示 ID。 */
async function showFailedDetail(failedIds: number[], label: string): Promise<void> {
  if (failedIds.length === 0) {
    return
  }
  const nameById = new Map(pendingList.value.map((u) => [u.id, u.username]))
  const lines = failedIds.map((id) => `- ${nameById.get(id) ?? `#${id}（不在当前列表）`}`)
  try {
    await ElMessageBox.alert(lines.join('\n'), `批量${label}失败的 ${failedIds.length} 个用户`, {
      confirmButtonText: '知道了',
      customClass: 'admin-users-view__failed-dialog'
    })
  } catch {
    // 用户关闭弹窗无需处理。
  }
}

/**
 * 通过用户。无二次确认 — admin 频繁审批场景下多一步会拖慢。
 */
async function approve(user: CurrentUser): Promise<void> {
  busyId.value = user.id
  busyAction.value = 'approve'
  try {
    await approveUser(user.id)
    ElMessage.success(`已通过 ${user.username}`)
    removeFromList(user.id)
  } catch (error) {
    ElMessage.error(explainError(error))
  } finally {
    busyId.value = null
    busyAction.value = null
  }
}

/**
 * 拒绝用户(el-popconfirm 已二次确认)。
 */
async function reject(user: CurrentUser): Promise<void> {
  busyId.value = user.id
  busyAction.value = 'reject'
  try {
    await rejectUser(user.id)
    ElMessage.success(`已拒绝 ${user.username}`)
    removeFromList(user.id)
  } catch (error) {
    ElMessage.error(explainError(error))
  } finally {
    busyId.value = null
    busyAction.value = null
  }
}

/** 单行审核成功后,本地移除该行并同步总数,避免整页重拉打断分页位置。 */
function removeFromList(id: number): void {
  pendingList.value = pendingList.value.filter((u) => u.id !== id)
  pendingTotal.value = Math.max(0, pendingTotal.value - 1)
  if (pendingList.value.length === 0 && page.value > 1) {
    page.value -= 1
    void fetchPending().catch((error) => ElMessage.error(explainError(error)))
  }
}

/**
 * ApiBusinessError → 用户提示。
 * 错误码语义参考 OpenAPI ErrorResponse + 后端实际探测结果。
 */
function explainError(error: unknown): string {
  if (error instanceof ApiBusinessError) {
    if (error.code === ErrorCode.RESOURCE_CONFLICT) {
      return '该用户已被审核,请刷新列表查看最新状态'
    }
    if (error.code === ErrorCode.RESOURCE_NOT_FOUND) {
      return '用户不存在或已被删除'
    }
    if (error.code === ErrorCode.FORBIDDEN) {
      return '当前账号无权操作'
    }
    if (error.code === ErrorCode.INVALID_REQUEST_PARAMETER) {
      return '参数不合法'
    }
    return error.message || '操作失败'
  }
  return '网络异常,请稍后重试'
}

onMounted(() => {
  void fetchPending().catch((error) => ElMessage.error(explainError(error)))
})
</script>

<style scoped>
.admin-users-view {
  max-width: 1100px;
  margin: 0 auto;
}

.admin-users-view__card {
  background: var(--susu-surface-soft);
  backdrop-filter: blur(20px) saturate(180%);
  -webkit-backdrop-filter: blur(20px) saturate(180%);
  border: 1px solid var(--susu-border-glass);
  border-radius: 16px;
  box-shadow:
    0 12px 32px rgba(183, 50, 92, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.85);
}

.admin-users-view__card :deep(.el-card__body) {
  padding: 20px;
}

.admin-users-view__summary {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.admin-users-view__tabs {
  flex: none;
}

.admin-users-view__search {
  max-width: 280px;
}

.admin-users-view__batch {
  margin-left: auto;
  display: flex;
  gap: 8px;
  align-items: center;
}

.admin-users-view__summary-label {
  font-size: 14px;
  font-weight: 600;
  color: var(--susu-ink);
}

.admin-users-view__refresh {
  background: linear-gradient(135deg, #ff5b8a 0%, #b7325c 100%) !important;
  border: none !important;
  color: #fff !important;
  box-shadow: 0 6px 14px rgba(255, 91, 138, 0.3) !important;
}

.admin-users-view__refresh:hover {
  background: linear-gradient(135deg, #ff7aa3 0%, #c8426f 100%) !important;
  box-shadow: 0 10px 20px rgba(255, 91, 138, 0.45) !important;
}

.admin-users-view__refresh svg {
  width: 16px;
  height: 16px;
  margin-right: 4px;
  vertical-align: -2px;
}

.admin-users-view__table {
  border-radius: 8px;
  overflow: hidden;
}

.admin-users-view__pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>