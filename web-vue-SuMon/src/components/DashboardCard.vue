<template>
  <el-card
    class="dashboard-view__card dashboard-view__card--glass"
    shadow="never"
  >
    <slot />
  </el-card>
</template>

<script setup lang="ts">
/**
 * Dashboard 玻璃卡片外壳。
 * 抽取共享的玻璃形态样式(el-card :deep(...) + 背景 / 圆角 / 阴影),
 * 让父组件 DashboardView 只管布局,卡片自身管样式。
 *
 * @slot 默认 slot:卡片 body 内容
 */
</script>

<style scoped>
</style>