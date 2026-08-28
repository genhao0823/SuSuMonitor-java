import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getLatestMetrics, getMetricsHistory } from '@/api/metrics'
import type { MetricsHistory, MetricsLatest } from '@/types/metrics'

/** 管理监控页面的最新值、历史数据和实时连接状态。 */
export const useMetricsStore = defineStore('metrics', () => {
  const latest = ref<MetricsLatest | null>(null)
  const history = ref<MetricsHistory[]>([])
  const loading = ref(false)
  const connected = ref(false)
  const error = ref<string | null>(null)
  /** 当前历史查询时间窗口，默认最近 24 小时；图表与表格共用。 */
  const timeRange = ref<[Date, Date]>([new Date(Date.now() - 24 * 3600_000), new Date()])

  /** 加载最新值与指定时间窗口的历史；不传时间窗口时沿用上一次窗口。 */
  async function load(serverId: number, start?: Date, end?: Date): Promise<void> {
    if (start) {
      timeRange.value = [start, end ?? new Date()]
    }
    const [startTime, endTime] = timeRange.value
    loading.value = true
    error.value = null
    try {
      const [latestResponse, historyResponse] = await Promise.all([
        getLatestMetrics(serverId),
        getMetricsHistory(serverId, startTime.toISOString(), endTime.toISOString(), 1, 100)
      ])
      latest.value = latestResponse.data
      // 按采集时间升序，保证 ECharts 时间轴顺序稳定。
      history.value = historyResponse.data.items
        .slice()
        .sort((a, b) => new Date(a.collected_at).getTime() - new Date(b.collected_at).getTime())
    } catch (reason) {
      error.value = reason instanceof Error ? reason.message : '指标加载失败'
    } finally {
      loading.value = false
    }
  }

  function applyRealtime(value: MetricsLatest): void {
    latest.value = value
  }

  function setConnected(value: boolean): void {
    connected.value = value
  }

  function reset(): void {
    latest.value = null
    history.value = []
    connected.value = false
    error.value = null
    timeRange.value = [new Date(Date.now() - 24 * 3600_000), new Date()]
  }

  return { latest, history, loading, connected, error, timeRange, load, applyRealtime, setConnected, reset }
})
