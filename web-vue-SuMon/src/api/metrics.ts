import apiClient from '@/api/client'
import type { ApiResponse, PageResult } from '@/types/api'
import type { MetricsHistory, MetricsLatest } from '@/types/metrics'

/** 查询服务器最新固定宽表指标。 */
export function getLatestMetrics(serverId: number): Promise<ApiResponse<MetricsLatest>> {
  return apiClient.get<ApiResponse<MetricsLatest>>(`/servers/${serverId}/metrics/latest`).then((r) => r.data)
}

/** 查询服务器历史固定宽表指标。 */
export function getMetricsHistory(
  serverId: number,
  startTime: string,
  endTime: string,
  page = 1,
  pageSize = 100
): Promise<ApiResponse<PageResult<MetricsHistory>>> {
  return apiClient.get<ApiResponse<PageResult<MetricsHistory>>>(`/servers/${serverId}/metrics`, {
    params: { start_time: startTime, end_time: endTime, page, page_size: pageSize }
  }).then((r) => r.data)
}
