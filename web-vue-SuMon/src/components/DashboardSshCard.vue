<template>
  <el-card
    class="dashboard-ssh-card liquid-glass-card"
    shadow="never"
  >
    <template #header>
      <div class="dashboard-ssh-card__header">
        <div class="dashboard-ssh-card__title">
          <TushanFoxMark
            :size="28"
            alt="涂山苏苏·SSH 测试"
          />
          <span>最近 SSH 测试</span>
        </div>
        <span class="liquid-badge liquid-badge--info">{{ history.length }} 次</span>
      </div>
    </template>
    <el-skeleton
      v-if="loading"
      :rows="5"
      animated
    />
    <template v-else-if="error">
      <p class="dashboard-ssh-card__error">
        {{ error }}
      </p>
    </template>
    <el-empty
      v-else-if="history.length === 0"
      description="暂无 SSH 测试记录"
      :image-size="64"
    >
      <p class="dashboard-ssh-card__hint">
        在服务器详情页执行 SSH 连接测试后会在此展示最近结果。
      </p>
    </el-empty>
    <template v-else>
      <div class="dashboard-ssh-card__list">
        <div
          v-for="item in history"
          :key="item.tested_at"
          class="dashboard-ssh-card__row"
        >
          <span
            class="liquid-badge"
            :class="item.connected ? 'liquid-badge--ok' : 'liquid-badge--down'"
          >
            {{ item.connected ? '成功' : '失败' }}
          </span>
          <span
            v-if="!item.connected"
            class="dashboard-ssh-card__code"
            :title="`业务错误码 ${item.error_code ?? '-'}`"
          >
            {{ item.error_code ?? '-' }}
          </span>
          <span class="dashboard-ssh-card__duration">
            {{ item.duration_ms }}ms
          </span>
          <span class="dashboard-ssh-card__time">
            {{ formatDateTime(item.tested_at) }}
          </span>
        </div>
      </div>
      <p class="dashboard-ssh-card__hint">
        最近 {{ history.length }} 次测试（成功与失败均记录）。
      </p>
    </template>
  </el-card>
</template>

<script setup lang="ts">
import TushanFoxMark from '@/components/TushanFoxMark.vue'
import type { SshTestResult } from '@/types/api'
import { formatDateTime } from '@/utils/format'

defineProps<{
  history: SshTestResult[]
  loading: boolean
  error: string | null
}>()
</script>

<style scoped>
.dashboard-ssh-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.dashboard-ssh-card__title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14.5px;
  font-weight: 700;
  color: var(--susu-ink);
}

.dashboard-ssh-card__list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.dashboard-ssh-card__row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  font-size: 12.5px;
  border-top: 1px solid rgba(39, 39, 42, 0.08);
}

.dashboard-ssh-card__code {
  color: #f43f5e;
  font-family: monospace;
  font-weight: 600;
}

.dashboard-ssh-card__duration {
  color: #6d3b54;
  font-weight: 600;
}

.dashboard-ssh-card__time {
  margin-left: auto;
  color: #8a5872;
  font-size: 11.5px;
}

.dashboard-ssh-card__hint {
  margin-top: 10px;
  font-size: 11.5px;
  color: #8a5872;
}

.dashboard-ssh-card__error {
  font-size: 13px;
  color: #f43f5e;
}
</style>
