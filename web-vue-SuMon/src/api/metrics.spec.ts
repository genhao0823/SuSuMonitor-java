import { describe, it, expect, vi, beforeEach } from 'vitest'
import apiClient from '@/api/client'
import {
  getLatestMetrics,
  getLatestProcesses,
  getMetricsHistory,
  getResourcesLatest
} from '@/api/metrics'
import type { ApiResponse } from '@/types/api'

/** 对齐 ai-settings.spec.ts 的 spy 模式：拦截 axios 实例方法，断言 URL 与参数不漂移。 */
function successResponse<T>(data: T): ApiResponse<T> {
  return { code: 0, message: 'ok', data } as ApiResponse<T>
}

describe('api/metrics', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('getLatestMetrics 调 GET /servers/{id}/metrics/latest', async () => {
    const spy = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: successResponse({}) })
    await getLatestMetrics(7)
    expect(spy).toHaveBeenCalledWith('/servers/7/metrics/latest')
  })

  it('getLatestProcesses 调 GET /servers/{id}/processes/latest', async () => {
    const spy = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: successResponse({}) })
    await getLatestProcesses(7)
    expect(spy).toHaveBeenCalledWith('/servers/7/processes/latest')
  })

  it('getResourcesLatest 调 GET /servers/{id}/resources/latest（协议 v1.5）', async () => {
    const spy = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: successResponse({}) })
    await getResourcesLatest(7)
    expect(spy).toHaveBeenCalledWith('/servers/7/resources/latest')
  })

  it('getMetricsHistory 以 start_time/end_time/page/page_size 查询参数调用', async () => {
    const spy = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: successResponse({}) })
    await getMetricsHistory(7, '2026-09-15T00:00:00Z', '2026-09-15T01:00:00Z', 2, 50)
    expect(spy).toHaveBeenCalledWith('/servers/7/metrics', {
      params: { start_time: '2026-09-15T00:00:00Z', end_time: '2026-09-15T01:00:00Z', page: 2, page_size: 50 }
    })
  })
})
