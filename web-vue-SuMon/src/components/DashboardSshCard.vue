<template>
  <el-card
    class="dashboard-view__card dashboard-view__card--glass"
    shadow="never"
  >
    <template #header>
      <div class="dashboard-view__card-header">
        <div class="dashboard-view__card-title">
          <TushanFoxMark
            :size="28"
            alt="涂山苏苏·SSH 测试"
          />
          最近 SSH 测试
        </div>
        <span class="dashboard-view__badge dashboard-view__badge--info">{{ history.length }}</span>
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
          <el-tag
            :type="item.connected ? 'success' : 'danger'"
            size="small"
            effect="plain"
          >
            {{ item.connected ? '成功' : '失败' }}
          </el-tag>
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

/**
 * Dashboard SSH 测试历史卡。
 * 纯展示组件:数据由 DashboardView 通过 props 传入,不自行发起请求。
 */
defineProps<{
  history: SshTestResult[]
  loading: boolean
  error: string | null
}>()
</script>

<style scoped>
.dashboard-ssh-card__list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.dashboard-ssh-card__row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.dashboard-ssh-card__code {
  color: var(--el-color-danger);
  font-family: monospace;
}

.dashboard-ssh-card__duration {
  color: var(--el-text-color-secondary);
}

.dashboard-ssh-card__time {
  margin-left: auto;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.dashboard-ssh-card__hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.dashboard-ssh-card__error {
  font-size: 13px;
  color: var(--el-color-danger);
}
</style>
