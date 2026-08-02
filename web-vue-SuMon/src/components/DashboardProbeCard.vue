<template>
  <el-card
    class="probe-card dashboard-view__card dashboard-view__card--glass"
    shadow="never"
  >
    <template #header>
      <div class="probe-card__header dashboard-view__card-header">
        <div class="probe-card__title dashboard-view__card-title">
          <TushanFoxMark
            :size="32"
            :alt="`涂山苏苏·${title}`"
          />
          <span
            v-if="ok && pulse"
            class="dashboard-view__pulse"
            aria-hidden="true"
          />
          {{ title }}
        </div>
        <span
          class="dashboard-view__badge"
          :class="ok ? 'dashboard-view__badge--ok' : 'dashboard-view__badge--down'"
        >
          {{ ok ? okLabel : 'DOWN' }}
        </span>
      </div>
    </template>
    <el-skeleton
      v-if="loading"
      :rows="3"
      animated
    />
    <template v-else>
      <div class="probe-card__content">
        <div>
          <div class="dashboard-view__card-value">
            {{ detail || '后端不可达' }}
          </div>
          <div class="dashboard-view__card-hint">
            {{ hint }}
          </div>
          <div
            v-if="ok && (checkedAt || responseTimeMs !== null)"
            class="probe-card__meta"
          >
            <span v-if="checkedAt">检查于 {{ formatDateTime(checkedAt) }}</span>
            <span v-if="responseTimeMs !== null">浏览器观测 {{ responseTimeMs }} ms</span>
          </div>
        </div>
        <div
          class="probe-card__details"
          aria-label="本次检查详情"
        >
          <p class="probe-card__details-title">
            本次检查详情
          </p>
          <div class="probe-card__facts">
            <div
              v-for="fact in facts"
              :key="fact.label"
              class="probe-card__fact"
            >
              <span>{{ fact.label }}</span>
              <strong>{{ fact.value }}</strong>
            </div>
          </div>
        </div>
        <p class="probe-card__description">
          {{ description }}
        </p>
      </div>
    </template>
  </el-card>
</template>

<script setup lang="ts">
import TushanFoxMark from '@/components/TushanFoxMark.vue'
import { formatDateTime } from '@/utils/format'

defineProps<{
  title: string
  ok: boolean
  detail: string
  hint: string
  description: string
  facts: Array<{ label: string; value: string }>
  loading: boolean
  pulse?: boolean
  okLabel: string
  checkedAt?: string | null
  responseTimeMs?: number | null
}>()
</script>

<style scoped>
.probe-card__header { display: flex; align-items: center; justify-content: space-between; }
.probe-card__title { display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 600; color: #2a1626; }
.probe-card__content { display: flex; flex-direction: column; min-height: 236px; }
.probe-card__meta { display: flex; flex-wrap: wrap; gap: 6px 12px; margin-top: 16px; padding-top: 10px; border-top: 1px dashed rgba(183, 50, 92, 0.14); color: #8a5872; font-size: 11px; line-height: 1.5; }
.probe-card__details { margin-top: 20px; padding: 12px; background: rgba(255, 255, 255, 0.42); border: 1px solid rgba(183, 50, 92, 0.1); border-radius: 10px; }
.probe-card__details-title { margin: 0 0 9px; color: #6d3b54; font-size: 11px; font-weight: 700; letter-spacing: 0.5px; }
.probe-card__facts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.probe-card__fact { min-width: 0; }
.probe-card__fact span { display: block; color: #9b7c8e; font-size: 10px; line-height: 1.4; }
.probe-card__fact strong { display: block; margin-top: 3px; color: #6d3b54; font-size: 11px; line-height: 1.45; overflow-wrap: anywhere; }
.probe-card__description { margin: auto 0 0; padding-top: 16px; color: #6d3b54; font-size: 12px; line-height: 1.6; }
@media (max-width: 720px) { .probe-card__title { font-size: 12px; } .probe-card__facts { grid-template-columns: 1fr; } }
</style>
