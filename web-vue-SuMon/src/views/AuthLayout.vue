<template>
  <div
    class="auth-view"
    :class="{ 'auth-view--compact': isRegisterPage }"
  >
    <div
      class="auth-view__aurora"
      aria-hidden="true"
    >
      <span class="auth-view__orb auth-view__orb--rose" />
      <span class="auth-view__orb auth-view__orb--gold" />
      <span class="auth-view__orb auth-view__orb--violet" />
    </div>

    <div class="auth-view__brand">
      <div class="auth-view__brand-row">
        <span class="auth-view__brand-mark">Su</span>
        <span class="auth-view__brand-mark auth-view__brand-mark--accent">Su</span>
        <span class="auth-view__brand-name">Monitor</span>
      </div>
      <div class="auth-view__brand-tagline">
        {{ stageTagline }}
      </div>
    </div>

    <SushShell
      :panel-title="panelTitle"
      :panel-sub="panelSub"
      :footer-hint="footerHint"
      :logo-url="effectiveHeroImage"
      :logo-alt="heroAlt"
    >
      <slot />
    </SushShell>

    <SushQuote :stage-quotes="stageQuotes" />
  </div>
</template>

<script setup lang="ts">
/**
 * 认证页面共享布局(深夜极光版)。
 *
 * - 近黑底 + 玫粉/鎏金/紫罗兰三团极光光晕缓慢漂移,叠加细点阵纹理
 * - 左上角紧凑品牌标识(双色块 + Monitor + 标语)
 * - 中央悬浮磨砂卡片(由 SushShell 提供,顶部渐变环苏苏头像)
 * - 卡片下方一行可点击切换的签名引言(SushQuote)
 *
 * 子组件:
 * - SushQuote: 可点击切换的签名引言
 * - SushShell: 居中磨砂表单卡片
 *
 * 父组件只需提供 slot 内的表单字段与提交逻辑。
 */

import SushQuote from '@/components/SushQuote.vue'
import SushShell from '@/components/SushShell.vue'
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

/**
 * 注册页表单更长:矮视口(≤780px 高)下隐藏引言保证一屏放下,登录页不受影响。
 */
const isRegisterPage = computed(() => route.name === 'register')

const props = withDefaults(
  defineProps<{
    stageTagline: string
    stageQuotes: string[]
    panelTitle: string
    panelSub: string
    footerHint: string
    heroImage?: string | null
    heroImageFallback?: string | null
    heroAlt?: string
  }>(),
  {
    heroImage: null,
    heroImageFallback: null,
    heroAlt: '涂山苏苏'
  }
)

const heroImageFailed = ref(false)

/**
 * 当前实际渲染的头像 URL:
 * 1. 优先 heroImage
 * 2. 失败或缺失时降级 heroImageFallback
 * 3. 都缺失返回 null,卡片不渲染头像
 */
const effectiveHeroImage = computed<string | null>(() => {
  if (props.heroImage && !heroImageFailed.value) {
    return props.heroImage
  }
  if (props.heroImageFallback) {
    return props.heroImageFallback
  }
  return null
})
</script>

<style scoped>
/*
 * 认证页采用固定的「深夜极光」视觉,不随明暗主题切换:
 * 近黑底 + 品牌色光晕 + 细点阵,聚焦中央磨砂卡片。
 * 所有子组件(SushShell / SushQuote / 登录注册表单)通过下方 --auth-* 令牌取色。
 */
.auth-view {
  --auth-ink: #f5eef4;
  --auth-ink-soft: #b9a9b8;
  --auth-ink-muted: #9d8ca6;
  --auth-primary: #f43f7e;
  --auth-primary-bright: #ff6b9d;
  --auth-primary-deep: #d63468;
  --auth-gold: #f5b942;
  --auth-link: #ff9db8;
  --auth-card-bg: rgba(21, 16, 27, 0.78);
  --auth-card-border: rgba(255, 255, 255, 0.09);
  --auth-input-bg: rgba(255, 255, 255, 0.05);
  --auth-input-border: rgba(255, 255, 255, 0.1);
  --auth-focus-glow: rgba(255, 107, 157, 0.25);

  position: relative;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  min-height: 100vh;
  padding: 64px 20px 32px;
  overflow: hidden;
  background: #0d0a11;
}

/* 细点阵纹理:极低调的技术感底纹 */
.auth-view::before {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  background-image: radial-gradient(rgba(255, 255, 255, 0.05) 1px, transparent 1.4px);
  background-size: 26px 26px;
}

/* 极光氛围:三团大模糊光斑缓慢漂移(仅 transform,走 GPU 合成) */
.auth-view__aurora {
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
}

.auth-view__orb {
  position: absolute;
  width: 560px;
  height: 560px;
  border-radius: 50%;
  filter: blur(110px);
}

.auth-view__orb--rose {
  top: -180px;
  left: -120px;
  background: rgba(244, 63, 126, 0.28);
  animation: auth-orb-a 22s ease-in-out infinite alternate;
}

.auth-view__orb--gold {
  right: -160px;
  bottom: -220px;
  background: rgba(245, 185, 66, 0.13);
  animation: auth-orb-b 26s ease-in-out infinite alternate;
}

.auth-view__orb--violet {
  top: 28%;
  right: -8%;
  width: 460px;
  height: 460px;
  background: rgba(139, 92, 246, 0.16);
  animation: auth-orb-c 30s ease-in-out infinite alternate;
}

@keyframes auth-orb-a {
  from { transform: translate3d(0, 0, 0) scale(1); }
  to   { transform: translate3d(60px, 40px, 0) scale(1.08); }
}

@keyframes auth-orb-b {
  from { transform: translate3d(0, 0, 0) scale(1); }
  to   { transform: translate3d(-50px, -30px, 0) scale(1.05); }
}

@keyframes auth-orb-c {
  from { transform: translate3d(0, 0, 0) scale(1); }
  to   { transform: translate3d(-40px, 50px, 0) scale(1.1); }
}

/* 左上角紧凑品牌标识 */
.auth-view__brand {
  position: absolute;
  top: 28px;
  left: 32px;
  z-index: 2;
}

.auth-view__brand-row {
  display: flex;
  align-items: center;
}

.auth-view__brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  margin-right: 4px;
  border-radius: 8px;
  background: var(--auth-primary);
  color: #ffffff;
  font-size: 12px;
  font-weight: 800;
}

.auth-view__brand-mark--accent {
  background: var(--auth-gold);
  color: #4a283b;
}

.auth-view__brand-name {
  margin-left: 8px;
  color: var(--auth-ink);
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 3px;
}

.auth-view__brand-tagline {
  margin-top: 8px;
  color: var(--auth-ink-soft);
  font-size: 11px;
  letter-spacing: 2px;
}

@media (max-width: 960px) {
  .auth-view {
    padding: 96px 16px 32px;
  }

  .auth-view__brand {
    top: 18px;
    left: 18px;
  }

  .auth-view__brand-mark {
    width: 22px;
    height: 22px;
    font-size: 10px;
    border-radius: 6px;
  }

  .auth-view__brand-name {
    font-size: 13px;
    letter-spacing: 2px;
  }

  .auth-view__brand-tagline {
    font-size: 10px;
    letter-spacing: 1px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .auth-view__orb {
    animation: none;
  }
}

/* 矮视口下注册页隐藏引言,登录页保留 */
@media (max-height: 780px) {
  .auth-view--compact :deep(.sush-quote) {
    display: none;
  }
}
</style>
