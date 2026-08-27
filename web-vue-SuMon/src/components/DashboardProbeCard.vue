<template>
  <el-card
    class="probe-card liquid-glass-card"
    shadow="never"
  >
    <template #header>
      <div class="probe-card__header">
        <div class="probe-card__title">
          <TushanFoxMark
            :size="32"
            :alt="`涂山苏苏·${title}`"
          />
          <span
            v-if="ok && pulse"
            class="liquid-dot liquid-dot--ok"
            aria-hidden="true"
          />
          <span class="probe-card__title-text">{{ title }}</span>
        </div>
        <span
          class="liquid-badge"
          :class="ok ? 'liquid-badge--ok' : 'liquid-badge--down'"
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
        <div class="probe-card__status-box">
          <div class="probe-card__value">
            {{ detail || '后端不可达' }}
          </div>
          <div class="probe-card__hint">
            {{ hint }}
          </div>
          <div
            v-if="ok && (checkedAt || responseTimeMs !== null)"
            class="probe-card__meta"
          >
            <span v-if="checkedAt">检查于 {{ formatDateTime(checkedAt) }}</span>
            <span
              v-if="responseTimeMs !== null"
              class="probe-card__rt"
            >
              浏览器观测 {{ responseTimeMs }} ms
            </span>
          </div>
        </div>
        <div
          class="probe-card__details"
          aria-label="本次检查详情"
        >
          <p class="probe-card__details-title">
            <span class="liquid-dot liquid-dot--pink" />
            本次检查详情
          </p>
          <div class="probe-card__facts">
            <div
              v-for="fact in facts"
              :key="fact.label"
              class="probe-card__fact"
            >
              <span class="probe-card__fact-label">{{ fact.label }}</span>
              <strong class="probe-card__fact-val">{{ fact.value }}</strong>
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
.probe-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.probe-card__title {
  display: flex;
  align-items: center;
  gap: 10px;
}

.probe-card__title-text {
  font-size: 15px;
  font-weight: 700;
  color: #2a1626;
  letter-spacing: 0;
}

.probe-card__content {
  display: flex;
  flex-direction: column;
  min-height: 240px;
}

.probe-card__status-box {
  margin-bottom: 12px;
}

.probe-card__value {
  color: #2a1626;
  font-size: 22px;
  font-weight: 800;
  line-height: 1.35;
  letter-spacing: 0;
}

.probe-card__hint {
  margin-top: 4px;
  color: #8a5872;
  font-size: 12.5px;
}

.probe-card__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 14px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed rgba(183, 50, 92, 0.15);
  color: #8a5872;
  font-size: 11.5px;
}

.probe-card__rt {
  color: #2eb872;
  font-weight: 600;
}

.probe-card__details {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid rgba(39, 39, 42, 0.08);
}

.probe-card__details-title {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 0 10px;
  color: #6d3b54;
  font-size: 11.5px;
  font-weight: 700;
  letter-spacing: 0;
}

.probe-card__facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.probe-card__fact {
  min-width: 0;
  padding: 4px 10px;
  border-left: 2px solid rgba(229, 72, 115, 0.18);
}

.probe-card__fact-label {
  display: block;
  color: #9b7c8e;
  font-size: 10.5px;
}

.probe-card__fact-val {
  display: block;
  margin-top: 2px;
  color: #2a1626;
  font-size: 12px;
  font-weight: 600;
  overflow-wrap: anywhere;
}

.probe-card__description {
  margin: auto 0 0;
  padding-top: 14px;
  color: #7b4d66;
  font-size: 12px;
  line-height: 1.55;
}

@media (max-width: 720px) {
  .probe-card__facts {
    grid-template-columns: 1fr;
  }
}
</style>
