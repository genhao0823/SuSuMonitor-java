<template>
  <el-card
    class="dashboard-admin-card liquid-glass-card"
    shadow="never"
  >
    <template #header>
      <div class="dashboard-admin-card__header">
        <div class="dashboard-admin-card__title">
          <span class="liquid-dot liquid-dot--pink" />
          管理员快速入口
        </div>
        <span class="dashboard-admin-card__meta">
          待审核用户:
          <el-tag
            :type="pendingCount > 0 ? 'warning' : 'info'"
            size="small"
            effect="dark"
          >
            {{ pendingCount }}
          </el-tag>
        </span>
      </div>
    </template>
    <div class="dashboard-admin-card__actions">
      <el-button
        type="primary"
        :disabled="pendingCount === 0"
        @click="emit('review')"
      >
        <el-icon aria-hidden="true">
          <UserFilled />
        </el-icon>
        前往审核
      </el-button>
      <el-button @click="emit('refresh')">
        <el-icon aria-hidden="true">
          <Refresh />
        </el-icon>
        重新加载
      </el-button>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { Refresh, UserFilled } from '@element-plus/icons-vue'

/**
 * Dashboard 管理员快速入口卡(admin-only)。
 *
 * @prop pendingCount 待审核用户数
 * @event review 点击"前往审核"
 * @event refresh 点击"重新加载"
 */

defineProps<{
  pendingCount: number
}>()

const emit = defineEmits<{
  (e: 'review'): void
  (e: 'refresh'): void
}>()
</script>

<style scoped>
.dashboard-admin-card__header,
.dashboard-admin-card__title,
.dashboard-admin-card__meta,
.dashboard-admin-card__actions {
  display: flex;
  align-items: center;
}

.dashboard-admin-card__header {
  justify-content: space-between;
  gap: 12px;
}

.dashboard-admin-card__title {
  gap: 8px;
  color: var(--susu-text-dark);
  font-size: 14px;
  font-weight: 700;
}

.dashboard-admin-card__meta {
  gap: 6px;
  color: var(--susu-text-muted);
  font-size: 12px;
}

.dashboard-admin-card__actions {
  gap: 8px;
  flex-wrap: wrap;
}
</style>
