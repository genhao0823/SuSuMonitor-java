<template>
  <DashboardCard>
    <div class="recent-alerts-card__header">
      <div>
        <p class="recent-alerts-card__eyebrow">
          近期活动
        </p>
        <h3>最新未读告警</h3>
      </div>
      <el-button
        link
        type="primary"
        @click="emit('view-alerts')"
      >
        查看全部
      </el-button>
    </div>

    <el-skeleton
      v-if="loading"
      :rows="4"
      animated
    />
    <p
      v-else-if="error"
      class="recent-alerts-card__state"
    >
      {{ error }}
    </p>
    <el-empty
      v-else-if="alerts.length === 0"
      description="暂无未读告警"
      :image-size="76"
    />
    <ul
      v-else
      class="recent-alerts-card__list"
    >
      <li
        v-for="alert in alerts"
        :key="alert.id"
      >
        <span
          class="recent-alerts-card__level"
          :class="`recent-alerts-card__level--${alert.level}`"
        >
          {{ alert.level === 'critical' ? '严重' : '警告' }}
        </span>
        <div class="recent-alerts-card__body">
          <strong>服务器 #{{ alert.server_id }} · {{ alert.metric }}</strong>
          <span>当前 {{ formatNumber(alert.current_value) }} / 阈值 {{ formatNumber(alert.threshold_value) }}</span>
        </div>
        <time>{{ formatDateTime(alert.triggered_at) }}</time>
      </li>
    </ul>
  </DashboardCard>
</template>

<script setup lang="ts">
import DashboardCard from '@/components/DashboardCard.vue'
import { formatDateTime } from '@/utils/format'
import type { AlertRecord } from '@/types/api'

defineProps<{
  alerts: AlertRecord[]
  loading: boolean
  error: string
}>()

const emit = defineEmits<{
  (e: 'view-alerts'): void
}>()

function formatNumber(value: number): string {
  return Number.isInteger(value) ? value.toString() : value.toFixed(2)
}
</script>

<style scoped>
.recent-alerts-card__header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.recent-alerts-card__eyebrow { margin: 0; color: #8a5872; font-size: 12px; }
h3 { margin: 4px 0 0; color: #2a1626; font-size: 17px; }
.recent-alerts-card__list { display: flex; flex-direction: column; gap: 4px; margin: 16px 0 0; padding: 0; list-style: none; }
.recent-alerts-card__list li { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; gap: 12px; align-items: center; padding: 12px 0; border-top: 1px dashed rgba(183, 50, 92, 0.14); }
.recent-alerts-card__body { display: flex; flex-direction: column; min-width: 0; gap: 4px; color: #8a5872; font-size: 12px; }
.recent-alerts-card__body strong { overflow: hidden; color: #2a1626; font-size: 14px; text-overflow: ellipsis; white-space: nowrap; }
.recent-alerts-card__level { padding: 3px 7px; border-radius: 7px; font-size: 11px; font-weight: 700; white-space: nowrap; }
.recent-alerts-card__level--critical { color: #fff; background: #d64b63; }
.recent-alerts-card__level--warning { color: #8a5410; background: rgba(231, 162, 58, 0.2); }
time { color: #9c7d8f; font-size: 12px; white-space: nowrap; }
.recent-alerts-card__state { margin: 24px 0 8px; color: #8a5872; text-align: center; }
@media (max-width: 640px) { .recent-alerts-card__list li { grid-template-columns: auto minmax(0, 1fr); } time { grid-column: 2; } }
</style>
