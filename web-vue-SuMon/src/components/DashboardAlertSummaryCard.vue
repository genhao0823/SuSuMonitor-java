<template>
  <el-card
    class="alert-summary-card liquid-glass-card"
    shadow="never"
  >
    <div class="alert-summary-card__header">
      <div>
        <div class="alert-summary-card__eyebrow">
          <span
            class="liquid-dot"
            :class="highestLevel === 'critical' ? 'liquid-dot--down' : 'liquid-dot--pink'"
          />
          <span>待处理事项</span>
        </div>
        <h3 class="alert-summary-card__title">
          未读告警统计
        </h3>
      </div>
      <span
        class="liquid-badge"
        :class="highestLevel === 'critical' ? 'liquid-badge--down' : 'liquid-badge--warn'"
      >
        {{ highestLevel === 'critical' ? '含严重告警' : '等待处理' }}
      </span>
    </div>

    <el-skeleton
      v-if="loading"
      :rows="2"
      animated
    />
    <template v-else>
      <div class="alert-summary-card__content">
        <strong class="alert-summary-card__num">{{ count }}</strong>
        <span class="alert-summary-card__unit">条待确认告警</span>
      </div>
      <el-button
        type="primary"
        class="alert-summary-card__btn"
        @click="emit('view-alerts')"
      >
        <span>查看告警记录</span>
        <el-icon aria-hidden="true">
          <ArrowRight />
        </el-icon>
      </el-button>
    </template>
  </el-card>
</template>

<script setup lang="ts">
import { ArrowRight } from '@element-plus/icons-vue'

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
.alert-summary-card {
  padding: 4px;
}

.alert-summary-card__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.alert-summary-card__eyebrow {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  color: #8a5872;
  font-size: 11.5px;
  font-weight: 600;
}

.alert-summary-card__title {
  margin: 4px 0 0;
  color: #2a1626;
  font-size: 17px;
  font-weight: 700;
}

.alert-summary-card__content {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin: 20px 0 16px;
  color: #6d3b54;
}

.alert-summary-card__num {
  color: var(--susu-primary-deep);
  font-size: 42px;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
  line-height: 1;
}

.alert-summary-card__unit {
  font-size: 13px;
  font-weight: 600;
  color: #8a5872;
}

.alert-summary-card__btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
</style>
