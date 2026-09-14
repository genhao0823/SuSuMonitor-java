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

/** 单个 Top 进程条目（协议 v1.4，只含进程名与占用比例）。 */
export interface ProcessSample {
  pid: number
  name: string
  cpu_percent: number
  mem_percent: number
}

/** 实时 Top 进程快照（服务端内存保留，不落库，90 秒新鲜窗口）。 */
export interface ProcessSnapshot {
  server_id: number
  collected_at: string
  cpu_top: ProcessSample[]
  mem_top: ProcessSample[]
}
