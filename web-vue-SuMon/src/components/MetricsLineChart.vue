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
import type { AlertRule } from '@/types/api'

type MetricKey = 'cpu_percent' | 'memory_percent' | 'disk_percent' | 'net_rx' | 'net_tx'

interface Props {
  /** 历史数据数组（组件内部按 collected_at 升序绘制）。 */
  data: MetricsHistory[]
  /** 要绘制的指标 key 列表。 */
  metrics: MetricKey[]
  /** 叠加的告警规则（前端已按当前服务器与 enabled 过滤），按 metric 匹配画阈值线。 */
  rules?: AlertRule[]
  /** 当前服务器 ID，用于过滤规则（server_id 为 null 表示全局规则）。 */
  serverId?: number
  title?: string
  height?: string
}

const props = withDefaults(defineProps<Props>(), {
  rules: () => [],
  serverId: 0,
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

/** 图表指标 key → 告警规则 metric 枚举；网络指标无对应告警规则。 */
const RULE_METRIC: Record<MetricKey, string | null> = {
  cpu_percent: 'cpu',
  memory_percent: 'memory',
  disk_percent: 'disk',
  net_rx: null,
  net_tx: null
}

const chartRef = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null

/** 全部指标为百分比时固定 y 轴 0-100，字节指标交给 ECharts 自动缩放。 */
function percentOnly(): boolean {
  return props.metrics.length > 0 && props.metrics.every((key) => key.endsWith('_percent'))
}

/** 匹配当前服务器（含全局规则）且启用的阈值线数据。 */
function thresholdLines(metric: MetricKey): Array<{ yAxis: number; label: string; color: string }> {
  const ruleMetric = RULE_METRIC[metric]
  if (ruleMetric === null) {
    return []
  }
  return props.rules
    .filter((rule) => rule.enabled && rule.metric === ruleMetric
      && (rule.server_id === null || rule.server_id === props.serverId))
    .map((rule) => ({
      yAxis: rule.threshold_value,
      label: `${rule.level === 'critical' ? '严重' : '警告'} ${rule.threshold_value}`,
      color: rule.level === 'critical' ? '#f56c6c' : '#e6a23c'
    }))
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
    series: props.metrics.map((key) => {
      const lines = thresholdLines(key)
      return {
        name: METRIC_LABELS[key],
        type: 'line',
        smooth: true,
        showSymbol: false,
        connectNulls: true,
        ...(lines.length > 0
          ? {
              markLine: {
                silent: true,
                symbol: 'none',
                data: lines.map((line) => ({
                  yAxis: line.yAxis,
                  label: { formatter: line.label, color: line.color },
                  lineStyle: { type: 'dashed' as const, color: line.color }
                }))
              }
            }
          : {}),
        data: props.data
          .map((item) => [new Date(item.collected_at).getTime(), item[key]])
          .filter((entry): entry is [number, number] =>
            entry[1] !== null && entry[1] !== undefined)
      }
    })
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
  () => [props.data, props.metrics, props.rules] as const,
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
