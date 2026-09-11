<template>
  <div
    class="sush-quote"
    role="button"
    tabindex="0"
    :aria-label="`签名引言,共 ${stageQuotes.length} 句,点击换一句。当前第 ${quoteIndex + 1} 句`"
    @click="advanceQuote"
    @keydown.enter.prevent="advanceQuote"
    @keydown.space.prevent="advanceQuote"
  >
    <transition name="quote-fade">
      <p
        :key="quoteIndex"
        class="sush-quote__text"
      >
        {{ stageQuotes[quoteIndex] }}
      </p>
    </transition>
    <p class="sush-quote__author">
      — 涂山 苏苏
    </p>
    <span class="sush-quote__hint">
      <svg
        viewBox="0 0 16 16"
        xmlns="http://www.w3.org/2000/svg"
        aria-hidden="true"
      >
        <path
          d="M8 3 A5 5 0 1 1 3 8"
          fill="none"
          stroke="currentColor"
          stroke-width="1.5"
          stroke-linecap="round"
        />
        <path
          d="M3 4 L3 8 L7 8"
          fill="none"
          stroke="currentColor"
          stroke-width="1.5"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
      </svg>
      <span>点一下,换一句</span>
    </span>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'

/**
 * 涂山苏苏签名引言:认证卡片下方的一行小字,可点击/键盘切换。
 *
 * @prop stageQuotes 引言池
 * @event click 点击切换
 * @event keydown.enter 键盘 Enter 切换
 * @event keydown.space 键盘 Space 切换
 */

const props = defineProps<{
  stageQuotes: string[]
}>()

/**
 * 当前展示的引言索引。onMounted 随机抽初值,点击/键盘事件后由 advanceQuote 推进。
 */
const quoteIndex = ref(0)

/**
 * 推进到下一句,索引到尾后回 0。
 * 由点击、Enter、Space 三种交互触发。
 */
function advanceQuote(): void {
  if (props.stageQuotes.length === 0) {
    return
  }
  quoteIndex.value = (quoteIndex.value + 1) % props.stageQuotes.length
}

onMounted(() => {
  if (props.stageQuotes.length > 0) {
    quoteIndex.value = Math.floor(Math.random() * props.stageQuotes.length)
  }
})
</script>

<style scoped>
/* 卡片下方的一行极简引言:色彩令牌继承自 AuthLayout 的 --auth-* */
.sush-quote {
  position: relative;
  z-index: 1;
  max-width: 560px;
  text-align: center;
  color: var(--auth-ink-soft);
  cursor: pointer;
  user-select: none;
  padding: 6px 14px;
  border-radius: 10px;
  outline: none;
  transition: color 0.2s ease, background 0.2s ease;
}

.sush-quote:hover,
.sush-quote:focus-visible {
  color: var(--auth-ink-soft);
  background: rgba(255, 255, 255, 0.04);
}

.sush-quote__text {
  margin: 0;
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 1px;
  font-style: italic;
  transition: color 0.2s ease;
}

.sush-quote:hover .sush-quote__text,
.sush-quote:focus-visible .sush-quote__text {
  color: var(--auth-link);
}

.quote-fade-enter-active,
.quote-fade-leave-active {
  transition: opacity 0.25s ease, transform 0.25s ease;
}

.quote-fade-enter-from {
  opacity: 0;
  transform: translateY(6px);
}

.quote-fade-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

.sush-quote__author {
  margin: 4px 0 0;
  font-size: 10px;
  opacity: 0.75;
  letter-spacing: 1px;
}

.sush-quote__hint {
  position: absolute;
  top: calc(100% + 2px);
  left: 50%;
  transform: translateX(-50%);
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 10px;
  letter-spacing: 1px;
  color: var(--auth-link);
  opacity: 0;
  transition: opacity 0.2s ease;
  white-space: nowrap;
  pointer-events: none;
}

.sush-quote:hover .sush-quote__hint,
.sush-quote:focus-visible .sush-quote__hint {
  opacity: 0.8;
}

.sush-quote__hint svg {
  width: 11px;
  height: 11px;
}

@media (max-width: 480px) {
  .sush-quote {
    max-width: 100%;
  }

  .sush-quote__text {
    font-size: 11px;
  }
}
</style>
