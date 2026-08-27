<template>
  <div class="server-spark-line">
    <svg
      class="server-spark-line__svg"
      viewBox="0 0 100 30"
      preserveAspectRatio="none"
      aria-hidden="true"
    >
      <defs>
        <linearGradient
          id="serverSparkFill"
          x1="0"
          x2="0"
          y1="0"
          y2="1"
        >
          <stop
            offset="0%"
            stop-color="#ff5b8a"
            stop-opacity="0.35"
          />
          <stop
            offset="100%"
            stop-color="#ff5b8a"
            stop-opacity="0"
          />
        </linearGradient>
      </defs>
      <path
        v-if="sparkAreaPath"
        :d="sparkAreaPath"
        fill="url(#serverSparkFill)"
      />
      <polyline
        v-if="sparkLinePoints"
        :points="sparkLinePoints"
        fill="none"
        stroke="#ff5b8a"
        stroke-width="1.5"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
    </svg>
    <div
      v-if="showMeta && delta !== 0"
      class="server-spark-line__meta"
    >
      <span
        v-if="label"
        class="server-spark-line__label"
      >{{ label }}</span>
      <span
        class="server-spark-line__delta"
        :class="delta >= 0 ? 'server-spark-line__delta--up' : 'server-spark-line__delta--down'"
      >{{ delta >= 0 ? '+' : '' }}{{ deltaLabel }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

/**
 * ServerListView spark line 通用组件。
 *
 * 传入历史点数组(数值),渲染 SVG 折线 + 填充区域 + 趋势 delta。
 * 数据点不足 2 个时不渲染(避免无意义折线)。
 *
 * @prop data 历史数据点(至少 2 个才有意义)
 * @prop label 可选标签(如 "CPU 趋势")
 * @prop showMeta 是否显示趋势 meta(默认 true)
 */

const props = withDefaults(
  defineProps<{
    data: number[]
    label?: string
    showMeta?: boolean
  }>(),
  {
    label: '',
    showMeta: true
  }
)

/**
 * spark line 折线点字符串(viewBox 0..100 宽,0..30 高)。
 * 数据归一化到 4..28 区间(留 2px padding)。
 */
const sparkLinePoints = computed<string>(() => {
  const data = props.data
  if (data.length < 2) {
    return ''
  }
  const max = Math.max(...data, 1)
  const min = Math.min(...data, 0)
  const range = Math.max(max - min, 1)
  const stepX = 100 / (data.length - 1)
  return data
    .map((v, i) => `${(i * stepX).toFixed(1)},${(30 - ((v - min) / range) * 26 - 2).toFixed(1)}`)
    .join(' ')
})

/**
 * spark line 填充区域(折线到 30 高度的闭合)。
 */
const sparkAreaPath = computed<string>(() => {
  const points = sparkLinePoints.value.split(' ').filter(Boolean)
  if (points.length === 0) {
    return ''
  }
  return `M0,30 L${points.join(' L')} L100,30 Z`
})

/**
 * 末尾与首位的差值(趋势标签)。
 */
const delta = computed<number>(() => {
  const data = props.data
  if (data.length < 2) {
    return 0
  }
  return Math.round((data[data.length - 1] - data[0]) * 10) / 10
})

const deltaLabel = computed<string>(() =>
  Number.isInteger(delta.value) ? delta.value.toFixed(0) : delta.value.toFixed(1)
)
</script>

<style scoped>
.server-spark-line {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  min-width: 80px;
}

.server-spark-line__svg {
  display: block;
  width: 100%;
  height: 28px;
}

.server-spark-line__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
  font-size: 10px;
  letter-spacing: 0;
}

.server-spark-line__label {
  color: #8a5872;
  font-style: italic;
}

.server-spark-line__delta {
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.server-spark-line__delta--up {
  color: #2eb872;
}

.server-spark-line__delta--down {
  color: #f86c6c;
}
</style>
