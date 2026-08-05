<template>
  <div
    ref="chartRef"
    class="metrics-line-chart"
    :style="{ height }"
  />
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import type { MetricsHistory } from '@/types/metrics'

type MetricKey = 'cpu_percent' | 'memory_percent' | 'disk_percent' | 'net_rx' | 'net_tx'

interface Props {
  /** 历史数据数组（组件内部按 collected_at 升序绘制）。 */
  data: MetricsHistory[]
  /** 要绘制的指标 key 列表。 */
  metrics: MetricKey[]
  title?: string
  height?: string
}

const props = withDefaults(defineProps<Props>(), {
  title: '',
  height: '320px'
})

const METRIC_LABELS: Record<MetricKey, string> = {
  cpu_percent: 'CPU %',
  memory_percent: '内存 %',
  disk_percent: '磁盘 %',
  net_rx: '网络接收',
  net_tx: '网络发送'
}

const chartRef = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null

/** 全部指标为百分比时固定 y 轴 0-100，字节指标交给 ECharts 自动缩放。 */
function percentOnly(): boolean {
  return props.metrics.length > 0 && props.metrics.every((key) => key.endsWith('_percent'))
}

function buildOption(): echarts.EChartsOption {
  return {
    title: props.title
      ? { text: props.title, left: 'center', textStyle: { fontSize: 14, color: '#2a1626' } }
      : undefined,
    tooltip: { trigger: 'axis' },
    legend: { top: 26, data: props.metrics.map((key) => METRIC_LABELS[key]) },
    grid: { left: 56, right: 24, top: 58, bottom: 32 },
    xAxis: { type: 'time' },
    yAxis: {
      type: 'value',
      ...(percentOnly() ? { min: 0, max: 100 } : {})
    },
    series: props.metrics.map((key) => ({
      name: METRIC_LABELS[key],
      type: 'line',
      smooth: true,
      showSymbol: false,
      connectNulls: true,
      data: props.data
        .map((item) => [new Date(item.collected_at).getTime(), item[key]])
        .filter((entry): entry is [number, number] =>
          entry[1] !== null && entry[1] !== undefined)
    }))
  }
}

function resize(): void {
  chart?.resize()
}

onMounted(() => {
  if (!chartRef.value) return
  chart = echarts.init(chartRef.value)
  chart.setOption(buildOption())
  window.addEventListener('resize', resize)
})

watch(
  () => [props.data, props.metrics] as const,
  () => {
    chart?.setOption(buildOption(), true)
  },
  { deep: true }
)

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.metrics-line-chart {
  width: 100%;
}
</style>
