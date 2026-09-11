<template>
  <header class="dashboard-hero liquid-glass-card">
    <div class="dashboard-hero__text">
      <div class="dashboard-hero__badge">
        <span class="liquid-dot liquid-dot--pink" />
        <span>涂山运维指挥中心</span>
      </div>
      <h2 class="dashboard-hero__title">
        苏苏欢迎你回来,
        <strong class="dashboard-hero__name">
          {{ username ?? '小道士' }}
        </strong>
        !
      </h2>
      <p class="dashboard-hero__sub">
        涂山小队的服务器今日一切安好，各节点状态稳定运行中~
      </p>
    </div>
    <div class="dashboard-hero__meta">
      <el-tag
        v-if="role"
        :type="role === 'admin' ? 'danger' : 'info'"
        effect="dark"
        size="default"
        class="dashboard-hero__role-tag"
      >
        {{ roleLabel }}
      </el-tag>
      <el-tag
        v-if="reviewStatus"
        :type="reviewStatusTagType(reviewStatus)"
        effect="plain"
        size="default"
        class="dashboard-hero__status-tag"
      >
        {{ reviewStatusLabel(reviewStatus) }}
      </el-tag>
      <el-tooltip
        content="刷新数据"
        placement="top"
      >
        <el-button
          circle
          :loading="refreshing"
          class="dashboard-hero__refresh-btn"
          aria-label="刷新仪表盘"
          @click="emit('refresh')"
        >
          <el-icon aria-hidden="true">
            <Refresh />
          </el-icon>
        </el-button>
      </el-tooltip>
      <el-button
        :loading="loggingOut"
        class="dashboard-hero__logout-btn"
        aria-label="退出当前账号"
        @click="emit('logout')"
      >
        <el-icon aria-hidden="true">
          <SwitchButton />
        </el-icon>
        <span>退出登录</span>
      </el-button>
    </div>
  </header>
</template>

<script setup lang="ts">
import { Refresh, SwitchButton } from '@element-plus/icons-vue'
import { reviewStatusLabel, reviewStatusTagType } from '@/utils/format'
import type { ReviewStatus, UserRole } from '@/types/api'

defineProps<{
  username?: string
  role?: UserRole
  reviewStatus?: ReviewStatus
  roleLabel: string
  refreshing: boolean
  loggingOut: boolean
}>()

const emit = defineEmits<{
  (e: 'refresh'): void
  (e: 'logout'): void
}>()
</script>

<style scoped>
.dashboard-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 28px 32px;
  margin-bottom: 28px;
  background: var(--glass-bg-card) !important;
  backdrop-filter: blur(28px) saturate(185%);
  -webkit-backdrop-filter: blur(28px) saturate(185%);
  border: 1px solid var(--susu-border-glass) !important;
  border-radius: var(--glass-radius-card) !important;
  box-shadow: 0 20px 48px rgba(183, 50, 92, 0.12), inset 0 1px 0 rgba(255, 255, 255, 0.95);
  position: relative;
  overflow: hidden;
}

.dashboard-hero__text {
  flex: 1;
  min-width: 0;
  position: relative;
  z-index: 1;
}

.dashboard-hero__badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0;
  color: #b7325c;
  background: rgba(255, 91, 138, 0.08);
  padding: 3px 10px;
  border-radius: 999px;
  margin-bottom: 8px;
  border: 1px solid rgba(255, 91, 138, 0.15);
}

.dashboard-hero__title {
  margin: 0;
  font-size: 26px;
  font-weight: 800;
  color: var(--susu-ink);
  letter-spacing: 0;
  line-height: 1.35;
}

.dashboard-hero__name {
  color: var(--susu-primary-deep);
  font-weight: 900;
  padding: 0 4px;
}

.dashboard-hero__sub {
  margin: 8px 0 0;
  font-size: 13.5px;
  color: #6d3b54;
  letter-spacing: 0;
}

.dashboard-hero__meta {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
  position: relative;
  z-index: 1;
}

.dashboard-hero__role-tag,
.dashboard-hero__status-tag {
  border-radius: 999px;
  padding: 0 12px;
  height: 32px;
  line-height: 30px;
  font-weight: 600;
}

.dashboard-hero__refresh-btn {
  width: 42px;
  height: 42px;
  padding: 0;
  background: var(--susu-primary-deep);
  border: none;
  color: #fff;
  box-shadow: 0 6px 16px rgba(255, 91, 138, 0.4);
  transition:
    transform 180ms ease,
    box-shadow 180ms ease,
    background-color 180ms ease;
}

.dashboard-hero__refresh-btn:hover:not(.is-loading) {
  background: var(--susu-primary);
  box-shadow: 0 8px 24px rgba(255, 91, 138, 0.55);
  transform: translateY(-2px) scale(1.05);
}

.dashboard-hero__refresh-btn :deep(.el-icon) {
  width: 20px;
  height: 20px;
  transition: transform 0.8s ease;
}

.dashboard-hero__refresh-btn:hover:not(.is-loading) :deep(.el-icon) {
  transform: rotate(360deg);
}

.dashboard-hero__logout-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 16px;
  height: 42px;
  font-size: 13.5px;
  font-weight: 600;
  letter-spacing: 0;
  color: var(--susu-accent-deep);
  background: var(--susu-surface);
  border: 1px solid rgba(183, 50, 92, 0.25);
  border-radius: 8px;
  backdrop-filter: blur(10px);
  box-shadow: 0 4px 12px rgba(183, 50, 92, 0.08), inset 0 1px 0 rgba(255, 255, 255, 0.9);
  transition:
    color 180ms ease,
    background-color 180ms ease,
    border-color 180ms ease,
    box-shadow 180ms ease,
    transform 180ms ease;
}

.dashboard-hero__logout-btn:hover {
  color: #fff;
  background: var(--susu-primary-deep);
  border-color: transparent;
  box-shadow: 0 6px 18px rgba(183, 50, 92, 0.35);
  transform: translateY(-2px);
}

.dashboard-hero__logout-btn :deep(.el-icon) {
  width: 18px;
  height: 18px;
}

@media (max-width: 768px) {
  .dashboard-hero {
    flex-direction: column;
    align-items: stretch;
    padding: 22px 24px;
  }

  .dashboard-hero__meta {
    justify-content: flex-start;
    flex-wrap: wrap;
  }

  .dashboard-hero__title {
    font-size: 22px;
  }
}
</style>
