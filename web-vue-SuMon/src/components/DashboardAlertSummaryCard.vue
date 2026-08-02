<template>
  <DashboardCard>
    <div class="alert-summary-card__header">
      <div>
        <p class="alert-summary-card__eyebrow">
          需要关注
        </p>
        <h3>未读告警</h3>
      </div>
      <el-tag
        :type="highestLevel === 'critical' ? 'danger' : 'warning'"
        effect="dark"
      >
        {{ highestLevel === 'critical' ? '含严重告警' : '等待处理' }}
      </el-tag>
    </div>

    <el-skeleton
      v-if="loading"
      :rows="2"
      animated
    />
    <template v-else>
      <div class="alert-summary-card__content">
        <strong>{{ count }}</strong>
        <span>条未读告警</span>
      </div>
      <el-button
        type="primary"
        plain
        @click="emit('view-alerts')"
      >
        查看告警记录
      </el-button>
    </template>
  </DashboardCard>
</template>

<script setup lang="ts">
import DashboardCard from '@/components/DashboardCard.vue'

withDefaults(
  defineProps<{
    count: number
    highestLevel?: 'warning' | 'critical'
    loading: boolean
  }>(),
  { highestLevel: 'warning' }
)

const emit = defineEmits<{
  (e: 'view-alerts'): void
}>()
</script>

<style scoped>
.alert-summary-card__header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.alert-summary-card__eyebrow { margin: 0; color: #8a5872; font-size: 12px; }
h3 { margin: 4px 0 0; color: #2a1626; font-size: 17px; }
.alert-summary-card__content { display: flex; align-items: baseline; gap: 8px; margin: 24px 0 18px; color: #6d3b54; }
.alert-summary-card__content strong { color: #b7325c; font-size: 38px; font-variant-numeric: tabular-nums; line-height: 1; }
</style>
