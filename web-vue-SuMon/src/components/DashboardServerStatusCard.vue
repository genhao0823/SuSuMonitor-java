<template>
  <DashboardCard>
    <div class="server-status-card__header">
      <div>
        <p class="server-status-card__eyebrow">
          运行概览
        </p>
        <h3>服务器状态</h3>
      </div>
      <el-button
        link
        type="primary"
        @click="emit('view-servers')"
      >
        查看服务器
      </el-button>
    </div>

    <el-skeleton
      v-if="loading"
      :rows="2"
      animated
    />
    <template v-else>
      <div class="server-status-card__stats">
        <div
          v-for="item in items"
          :key="item.key"
          class="server-status-card__stat"
        >
          <span
            class="server-status-card__dot"
            :class="`server-status-card__dot--${item.key}`"
            aria-hidden="true"
          />
          <span class="server-status-card__label">{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
        </div>
      </div>
      <p class="server-status-card__hint">
        {{ complete ? '已统计全部服务器' : `当前已统计前 ${sampledCount} 台服务器` }}
      </p>
    </template>
  </DashboardCard>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import DashboardCard from '@/components/DashboardCard.vue'

const props = defineProps<{
  online: number
  offline: number
  unknown: number
  sampledCount: number
  complete: boolean
  loading: boolean
}>()

const emit = defineEmits<{
  (e: 'view-servers'): void
}>()

const items = computed(() => [
  { key: 'online', label: '在线', value: props.online },
  { key: 'offline', label: '离线', value: props.offline },
  { key: 'unknown', label: '未知', value: props.unknown }
])
</script>

<style scoped>
.server-status-card__header,
.server-status-card__stat {
  display: flex;
  align-items: center;
}

.server-status-card__header {
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 20px;
}

.server-status-card__eyebrow,
.server-status-card__hint {
  margin: 0;
  color: #8a5872;
  font-size: 12px;
}

h3 {
  margin: 4px 0 0;
  color: #2a1626;
  font-size: 17px;
}

.server-status-card__stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.server-status-card__stat {
  flex-wrap: wrap;
  gap: 5px;
  min-width: 0;
  padding: 10px;
  background: rgba(255, 255, 255, 0.42);
  border-radius: 12px;
}

.server-status-card__stat strong {
  width: 100%;
  margin-top: 2px;
  color: #2a1626;
  font-size: 22px;
  font-variant-numeric: tabular-nums;
}

.server-status-card__label { color: #6d3b54; font-size: 12px; }
.server-status-card__dot { width: 8px; height: 8px; border-radius: 50%; }
.server-status-card__dot--online { background: #2eb872; }
.server-status-card__dot--offline { background: #9b8d96; }
.server-status-card__dot--unknown { background: #e7a23a; }
.server-status-card__hint { margin-top: 14px; }
</style>
