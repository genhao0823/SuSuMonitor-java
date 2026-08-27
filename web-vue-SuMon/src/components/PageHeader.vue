<template>
  <header class="page-header">
    <div class="page-header__main">
      <div class="page-header__badge">
        <span class="liquid-dot liquid-dot--pink" />
        <span class="page-header__badge-text">SuSu System</span>
      </div>
      <h1 class="page-header__title">
        {{ title }}
      </h1>
      <p
        v-if="subtitle"
        class="page-header__subtitle"
      >
        {{ subtitle }}
      </p>
    </div>
    <div
      v-if="$slots.actions"
      class="page-header__actions"
    >
      <slot name="actions" />
    </div>
  </header>
</template>

<script setup lang="ts">
/**
 * 通用页面标题组件。
 *
 * 在 MainLayout 内每个页面顶部放置,统一标题、副标题与右上角操作槽位。
 * 使用克制的品牌标记和分隔线，不把页面区段包装为嵌套卡片。
 *
 * @prop title 页面主标题,必填
 * @prop subtitle 页面副标题,可空
 */
defineProps<{
  title: string
  subtitle?: string
}>()
</script>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 24px;
  padding: 0 0 16px 16px;
  position: relative;
  border-bottom: 1px solid rgba(39, 39, 42, 0.1);
}

.page-header::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  width: 3px;
  height: 100%;
  background: var(--susu-primary);
  border-radius: 2px;
}

.page-header__main {
  min-width: 0;
}

.page-header__badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}

.page-header__badge-text {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0;
  color: var(--susu-primary-deep);
  text-transform: uppercase;
}

.page-header__title {
  margin: 0 0 4px;
  font-size: 22px;
  font-weight: 700;
  color: var(--susu-text-dark);
  letter-spacing: 0;
}

.page-header__subtitle {
  margin: 0;
  font-size: 13px;
  color: var(--susu-text-muted);
  line-height: 1.5;
}

.page-header__actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

@media (max-width: 640px) {
  .page-header {
    flex-direction: column;
    align-items: flex-start;
    padding: 0 0 12px 12px;
  }

  .page-header__actions {
    width: 100%;
    flex-wrap: wrap;
  }
}
</style>
