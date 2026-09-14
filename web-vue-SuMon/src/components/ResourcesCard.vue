<template>
  <el-card
    shadow="never"
    class="resources-card"
  >
    <template #header>
      <div class="resources-card__header">
        <span>磁盘 / 网卡</span>
        <span
          v-if="snapshot"
          class="resources-card__meta"
        >
          采样于 {{ formatDateTime(snapshot.collected_at) }}
        </span>
      </div>
    </template>
    <el-empty
      v-if="!snapshot"
      description="暂无数据：等待 Agent 上报，或 Agent 版本过旧/已关闭扩展资源采集"
      :image-size="64"
    />
    <el-tabs v-else>
      <el-tab-pane label="磁盘容量">
        <el-table
          :data="snapshot.disks"
          stripe
          size="small"
        >
          <el-table-column
            prop="mount_point"
            label="挂载点"
            min-width="180"
            show-overflow-tooltip
          />
          <el-table-column
            prop="device"
            label="设备"
            min-width="140"
            show-overflow-tooltip
          />
          <el-table-column
            label="容量"
            min-width="220"
          >
            <template #default="{ row }">
              <el-progress
                :percentage="usedPercent(row)"
                :stroke-width="10"
              />
              <span class="resources-card__bytes">
                已用 {{ formatBytes(row.total - row.free) }} / 总计 {{ formatBytes(row.total) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column
            label="剩余"
            width="110"
          >
            <template #default="{ row }">
              {{ formatBytes(row.free) }}
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="网卡速率">
        <el-table
          :data="snapshot.nics"
          stripe
          size="small"
        >
          <el-table-column
            prop="name"
            label="网卡"
            min-width="180"
            show-overflow-tooltip
          />
          <el-table-column
            label="接收"
            width="140"
          >
            <template #default="{ row }">
              {{ formatRate(row.rx_kbps) }}
            </template>
          </el-table-column>
          <el-table-column
            label="发送"
            width="140"
          >
            <template #default="{ row }">
              {{ formatRate(row.tx_kbps) }}
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import type { ServerResourcesSnapshot } from '@/types/metrics'
import { formatBytes, formatDateTime } from '@/utils/format'

/** 资源快照为只读渲染组件；数据获取与 WS 更新由 MetricsView 负责。 */
defineProps<{
  /** 最新资源快照；null 表示尚无数据（Agent 未上报或版本过旧）。 */
  snapshot: ServerResourcesSnapshot | null
}>()

/** 容量条辅助函数接受的磁盘行形态：只需读 total/free 两个数值字段。 */
interface DiskRowLike {
  total?: number | null
  free?: number | null
}

/** 计算磁盘已用百分比；容量为 0 时按 0 展示，避免除零产生 NaN。 */
function usedPercent(row: DiskRowLike): number {
  const total = row.total ?? 0
  const free = row.free ?? 0
  if (total <= 0) {
    return 0
  }
  const percent = ((total - free) / total) * 100
  return Math.round(percent * 10) / 10
}

/** 速率保留 2 位小数展示；非法值按 0 展示。 */
function formatRate(kbps: number): string {
  return `${Number.isFinite(kbps) ? kbps.toFixed(2) : '0.00'} kbps`
}
</script>

<style scoped>
.resources-card { margin-top: 16px; }
.resources-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.resources-card__meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.resources-card__bytes {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
