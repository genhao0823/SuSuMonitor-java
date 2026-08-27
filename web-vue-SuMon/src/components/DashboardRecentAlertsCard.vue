<template>
  <el-card
    class="recent-alerts-card liquid-glass-card"
    shadow="never"
  >
    <div class="recent-alerts-card__header">
      <div>
        <div class="recent-alerts-card__eyebrow">
          <span class="liquid-dot liquid-dot--pink" />
          <span>近期活动</span>
        </div>
        <h3 class="recent-alerts-card__title">
          最新未读告警
        </h3>
      </div>
      <el-button
        link
        type="primary"
        class="recent-alerts-card__view-all"
        @click="emit('view-alerts')"
      >
        <span>查看全部</span>
        <el-icon aria-hidden="true">
          <ArrowRight />
        </el-icon>
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
      description="暂无未读告警，系统运行平稳"
      :image-size="76"
    />
    <ul
      v-else
      class="recent-alerts-card__list"
    >
      <li
        v-for="alert in alerts"
        :key="alert.id"
        class="recent-alerts-card__item"
      >
        <span
          class="liquid-badge"
          :class="alert.level === 'critical' ? 'liquid-badge--down' : 'liquid-badge--warn'"
        >
          {{ alert.level === 'critical' ? '严重' : '警告' }}
        </span>
        <div class="recent-alerts-card__body">
          <strong class="recent-alerts-card__name">服务器 #{{ alert.server_id }} · {{ alert.metric }}</strong>
          <span class="recent-alerts-card__val">当前 <strong>{{ formatNumber(alert.current_value) }}</strong> / 阈值 {{ formatNumber(alert.threshold_value) }}</span>
        </div>
        <time class="recent-alerts-card__time">{{ formatDateTime(alert.triggered_at) }}</time>
      </li>
    </ul>
  </el-card>
</template>

<script setup lang="ts">
import { ArrowRight } from '@element-plus/icons-vue'
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
.recent-alerts-card__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.recent-alerts-card__eyebrow {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  color: #8a5872;
  font-size: 11.5px;
  font-weight: 600;
}

.recent-alerts-card__title {
  margin: 4px 0 0;
  color: #2a1626;
  font-size: 17px;
  font-weight: 700;
}

.recent-alerts-card__view-all {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-weight: 600;
}

.recent-alerts-card__list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin: 18px 0 0;
  padding: 0;
  list-style: none;
}

.recent-alerts-card__item {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 14px;
  align-items: center;
  padding: 12px 16px;
  border-top: 1px solid rgba(39, 39, 42, 0.08);
  transition:
    background-color 180ms ease,
    transform 180ms ease;
}

.recent-alerts-card__item:hover {
  background: rgba(229, 72, 115, 0.05);
  transform: translateX(2px);
}

.recent-alerts-card__body {
  display: flex;
  flex-direction: column;
  min-width: 0;
  gap: 3px;
}

.recent-alerts-card__name {
  overflow: hidden;
  color: #2a1626;
  font-size: 13.5px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recent-alerts-card__val {
  color: #7b4d66;
  font-size: 12px;
}

.recent-alerts-card__val strong {
  color: #b7325c;
}

.recent-alerts-card__time {
  color: #9c7d8f;
  font-size: 11.5px;
  white-space: nowrap;
}

.recent-alerts-card__state {
  margin: 24px 0 8px;
  color: #f43f5e;
  text-align: center;
  font-size: 13px;
}

@media (max-width: 640px) {
  .recent-alerts-card__item {
    grid-template-columns: auto minmax(0, 1fr);
  }
  .recent-alerts-card__time {
    grid-column: 2;
  }
}
</style>
