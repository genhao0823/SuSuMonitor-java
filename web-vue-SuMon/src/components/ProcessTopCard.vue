<template>
  <el-card
    shadow="never"
    class="process-top-card"
  >
    <template #header>
      <div class="process-top-card__header">
        <span>实时进程</span>
        <span
          v-if="snapshot"
          class="process-top-card__meta"
        >
          采样于 {{ formatDateTime(snapshot.collected_at) }}
        </span>
      </div>
    </template>
    <el-empty
      v-if="!snapshot"
      description="暂无数据：等待 Agent 上报，或 Agent 版本过旧/已关闭进程采集"
      :image-size="64"
    />
    <el-tabs v-else>
      <el-tab-pane label="CPU 排行">
        <el-table
          :data="snapshot.cpu_top"
          stripe
          size="small"
        >
          <el-table-column
            prop="pid"
            label="PID"
            width="90"
          />
          <el-table-column
            prop="name"
            label="进程名"
            min-width="200"
            show-overflow-tooltip
          />
          <el-table-column
            prop="cpu_percent"
            label="CPU %"
            width="110"
          />
          <el-table-column
            prop="mem_percent"
            label="内存 %"
            width="110"
          />
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="内存排行">
        <el-table
          :data="snapshot.mem_top"
          stripe
          size="small"
        >
          <el-table-column
            prop="pid"
            label="PID"
            width="90"
          />
          <el-table-column
            prop="name"
            label="进程名"
            min-width="200"
            show-overflow-tooltip
          />
          <el-table-column
            prop="cpu_percent"
            label="CPU %"
            width="110"
          />
          <el-table-column
            prop="mem_percent"
            label="内存 %"
            width="110"
          />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import type { ProcessSnapshot } from '@/types/metrics'
import { formatDateTime } from '@/utils/format'

/** 进程快照为只读渲染组件；数据获取与 WS 更新由 MetricsView 负责。 */
defineProps<{
  /** 最新进程快照；null 表示尚无数据（Agent 未上报或版本过旧）。 */
  snapshot: ProcessSnapshot | null
}>()
</script>

<style scoped>
.process-top-card { margin-top: 16px; }
.process-top-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.process-top-card__meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
