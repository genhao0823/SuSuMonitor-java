import { describe, it, expect, vi } from 'vitest'
import apiClient, { ApiBusinessError } from '@/api/client'
import { generateHealthReport, getHealthReport, listHealthReports } from '@/api/healthReport'
import type { AiHealthReport, ApiResponse, PageResult } from '@/types/api'
import { ErrorCode } from '@/types/error-code'

/**
 * 健康报告(F3)API 封装回归:验证请求 URL、query/body 参数传递、
 * 生成接口的超时与静默配置,以及 40400 折叠为 data=null 的空态语义。
 */
describe('healthReport api / HTTP wrappers', () => {
  it('listHealthReports 调 GET /ai/health-reports,page/page_size 走 query 传参', async () => {
    const pageData: PageResult<AiHealthReport> = {
      items: [],
      total: 0,
      page: 1,
      page_size: 20
    }
    const spy = vi.spyOn(apiClient, 'get').mockResolvedValue({
      data: { code: 0, message: 'success', data: pageData } as ApiResponse<PageResult<AiHealthReport>>
    })

    const result = await listHealthReports({ page: 2, page_size: 10 })

    expect(spy).toHaveBeenCalledWith('/ai/health-reports', {
      params: { page: 2, page_size: 10 }
    })
    expect(result.data.total).toBe(0)
  })

  it('generateHealthReport 调 POST /ai/health-reports/generate,body 携带 report_date 并带超时与静默配置', async () => {
    const report = { id: 9, report_date: '2026-09-11' } as AiHealthReport
    const spy = vi.spyOn(apiClient, 'post').mockResolvedValue({
      data: { code: 0, message: 'success', data: report } as ApiResponse<AiHealthReport>
    })

    const result = await generateHealthReport({ report_date: '2026-09-11' })

    expect(spy).toHaveBeenCalledWith(
      '/ai/health-reports/generate',
      { report_date: '2026-09-11' },
      { timeout: 180_000, silent: true }
    )
    expect(result.data.id).toBe(9)
  })

  it('generateHealthReport 省略日期时提交空对象,由后端取昨日', async () => {
    const spy = vi.spyOn(apiClient, 'post').mockResolvedValue({
      data: { code: 0, message: 'success', data: {} as AiHealthReport }
    })

    await generateHealthReport()

    expect(spy).toHaveBeenCalledWith('/ai/health-reports/generate', {}, expect.anything())
  })

  it('getHealthReport 遇 40400 时折叠为 data=null 的空态,不抛错', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue(
      new ApiBusinessError(ErrorCode.RESOURCE_NOT_FOUND, 'resource not found')
    )

    const result = await getHealthReport(404)

    expect(result.code).toBe(ErrorCode.RESOURCE_NOT_FOUND)
    expect(result.data).toBeNull()
  })

  it('getHealthReport 的其他业务错误(如 40300)原样向上抛出', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValue(
      new ApiBusinessError(ErrorCode.FORBIDDEN, 'forbidden')
    )

    await expect(getHealthReport(1)).rejects.toMatchObject({ code: ErrorCode.FORBIDDEN })
  })
})
