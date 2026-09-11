<template>
  <main class="sush-shell">
    <div class="sush-shell__card">
      <span
        v-if="effectiveLogoUrl"
        class="sush-shell__logo-ring"
      >
        <img
          :src="effectiveLogoUrl"
          :alt="logoAlt"
          class="sush-shell__logo"
          @error="onLogoError"
        >
      </span>
      <header class="sush-shell__header">
        <h1 class="sush-shell__title">
          {{ panelTitle }}
        </h1>
        <p class="sush-shell__sub">
          {{ panelSub }}
        </p>
      </header>

      <div class="sush-shell__form">
        <slot />
      </div>

      <footer class="sush-shell__footer">
        <span>{{ footerHint }}</span>
      </footer>
    </div>
  </main>
</template>

<script setup lang="ts">
/**
 * 认证页面居中磨砂表单卡片(深夜极光版)。
 *
 * - 顶部渐变环(玫粉→鎏金)苏苏头像,加载失败自动隐藏
 * - 近黑半透明磨砂卡片 + backdrop-filter,浮在极光背景之上
 * - 色彩令牌(--auth-*)由 AuthLayout 提供,认证页不随明暗主题切换
 *
 * @prop panelTitle 表单标题
 * @prop panelSub 表单副标
 * @prop footerHint 表单底部 hint
 * @prop logoUrl 头像 URL(可空,空则不渲染)
 * @prop logoAlt 头像 alt 文本
 */

import { computed, ref } from 'vue'

const props = withDefaults(
  defineProps<{
    panelTitle: string
    panelSub: string
    footerHint: string
    logoUrl?: string | null
    logoAlt?: string
  }>(),
  {
    logoUrl: null,
    logoAlt: '涂山苏苏'
  }
)

const logoFailed = ref(false)

const effectiveLogoUrl = computed<string | null>(() => {
  return logoFailed.value ? null : props.logoUrl
})

function onLogoError(): void {
  logoFailed.value = true
}
</script>

<style scoped>
.sush-shell {
  position: relative;
  z-index: 1;
  width: 100%;
  display: flex;
  justify-content: center;
}

.sush-shell__card {
  position: relative;
  width: 100%;
  max-width: 400px;
  padding: 28px 30px 22px;
  border-radius: 20px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.05) 0%, rgba(255, 255, 255, 0.015) 35%, transparent 65%),
    var(--auth-card-bg);
  backdrop-filter: blur(24px) saturate(130%);
  -webkit-backdrop-filter: blur(24px) saturate(130%);
  border: 1px solid var(--auth-card-border);
  box-shadow:
    0 24px 70px rgba(0, 0, 0, 0.55),
    inset 0 1px 0 rgba(255, 255, 255, 0.14);
}

.sush-shell__logo-ring {
  display: flex;
  width: 60px;
  height: 60px;
  margin: 0 auto 14px;
  padding: 3px;
  border-radius: 50%;
  background: linear-gradient(135deg, #ff6b9d 0%, #f43f7e 45%, #f5b942 100%);
  box-shadow: 0 8px 24px rgba(244, 63, 126, 0.35);
}

.sush-shell__logo {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 50%;
  display: block;
}

.sush-shell__header {
  text-align: center;
  margin-bottom: 18px;
}

.sush-shell__title {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 700;
  color: var(--auth-ink);
  letter-spacing: 2px;
}

.sush-shell__sub {
  margin: 0;
  font-size: 13px;
  color: var(--auth-ink-soft);
  letter-spacing: 1px;
}

.sush-shell__form {
  width: 100%;
}

.sush-shell__form :deep(.el-form-item) {
  margin-bottom: 12px;
}

.sush-shell__form :deep(.el-form-item__label) {
  color: var(--auth-ink-soft);
  font-weight: 500;
  font-size: 13px;
  padding-bottom: 6px;
}

.sush-shell__form :deep(.el-input__wrapper) {
  background: var(--auth-input-bg);
  border-radius: 12px;
  padding: 2px 14px;
  box-shadow:
    0 0 0 1px var(--auth-input-border) inset,
    inset 0 2px 4px rgba(0, 0, 0, 0.22);
  transition: box-shadow 0.2s, background 0.2s;
}

.sush-shell__form :deep(.el-input__wrapper:hover) {
  background: rgba(255, 255, 255, 0.08);
  box-shadow:
    0 0 0 1px rgba(255, 255, 255, 0.2) inset,
    inset 0 2px 4px rgba(0, 0, 0, 0.22);
}

.sush-shell__form :deep(.el-input__wrapper.is-focus) {
  background: rgba(255, 255, 255, 0.08);
  box-shadow:
    0 0 0 1.5px var(--auth-primary-bright) inset,
    0 0 12px var(--auth-focus-glow),
    inset 0 2px 4px rgba(0, 0, 0, 0.18);
}

.sush-shell__form :deep(.el-input__prefix-inner .el-icon) {
  color: var(--auth-ink-muted);
}

.sush-shell__form :deep(.el-input__inner) {
  height: 40px;
  font-size: 14px;
  color: var(--auth-ink);
}

.sush-shell__form :deep(.el-input__inner::placeholder) {
  color: var(--auth-ink-muted);
}

.sush-shell__form :deep(.el-input__icon) {
  color: var(--auth-ink-muted);
}

.sush-shell__form :deep(.el-input__icon:hover) {
  color: var(--auth-ink-soft);
}

.sush-shell__footer {
  margin-top: 14px;
  text-align: center;
  font-size: 11px;
  color: var(--auth-ink-soft);
  opacity: 0.85;
  letter-spacing: 1px;
}

/* 视口较矮(如 720p 笔记本)时进一步紧凑,保证一屏放下 */
@media (max-height: 820px) {
  .sush-shell__card {
    padding: 24px 28px 18px;
  }

  .sush-shell__logo-ring {
    width: 52px;
    height: 52px;
    margin-bottom: 12px;
  }

  .sush-shell__header {
    margin-bottom: 14px;
  }

  .sush-shell__form :deep(.el-form-item) {
    margin-bottom: 10px;
  }

  .sush-shell__footer {
    margin-top: 12px;
  }
}

@media (max-width: 480px) {
  .sush-shell__card {
    padding: 24px 20px 18px;
    border-radius: 18px;
  }
}
</style>
