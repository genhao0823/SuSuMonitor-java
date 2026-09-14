/** 固定宽表指标的最新值。 */
export interface MetricsLatest {
  server_id: number
  cpu_percent: number | null
  memory_percent: number | null
  memory_used: number | null
  memory_total: number | null
  disk_percent: number | null
  disk_used: number | null
  disk_total: number | null
  net_rx: number | null
  net_tx: number | null
  temperature: number | null
  load_avg: number | null
  collected_at: string
}

/** 固定宽表历史指标，字段与最新值一致。 */
export type MetricsHistory = MetricsLatest
