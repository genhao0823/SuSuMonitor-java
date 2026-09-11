import apiClient, { ApiBusinessError } from '@/api/client'
import { ErrorCode } from '@/types/error-code'
import type {
  AiHealthReport,
  ApiResponse,
  GenerateHealthReportRequest,
  PageResult
} from '@/types/api'

/**
 * 定时健康报告(F3)模块 API 封装。
 *
 * 字段命名与 OpenAPI `openapi-ai.json` 0.6.0 严格对齐(snake_case 出站),
 * 由 `src/types/api.d.ts` 中的类型保证;后端三个端点均要求 ROLE_ADMIN
 * (SecurityConfig 显式声明),鉴权失败由 `src/api/client.ts` 统一拦截。
 *
 * 静默约定:生成接口标记 `silent: true`,错误文案由调用方通过
 * {@link describeAiError}(复用 `@/api/ai` 的 AI 错误码映射,42906/50304/50401
 * 等口径一致)转成中文提示。
 *
 * 功能开关:后端 `susumonitor.ai.report.enabled` 默认关闭,关闭时 Controller
 * 不装配,请求得到 404(40400);调用方应把 40400 视为"功能未开启"。
 */

/**
 * 手动生成报告的前端超时上限(毫秒)。
 *
 * 生成链路包含只读聚合 + 单次 LLM 调用 + 落库,服务端最坏路径(连接 5s +
 * 读取 30s × 有限重试)高于问答的纯推理耗时,放宽到 180 秒。
 */
const REPORT_TIMEOUT_MS = 180_000

/**
 * 分页查询历史健康报告(按 report_date 倒序)。
 *
 * @param query 分页参数(page/page_size 均为后端必填,page_size 范围 1~100)
 * @returns 分页报告列表
 */
export function listHealthReports(query: {
  page: number
  page_size: number
}): Promise<ApiResponse<PageResult<AiHealthReport>>> {
  return apiClient
    .get<ApiResponse<PageResult<AiHealthReport>>>('/ai/health-reports', {
      params: { page: query.page, page_size: query.page_size }
    })
    .then((r) => r.data)
}

/**
 * 调用 GET /api/ai/health-reports/{id} 获取单份报告完整视图。
 *
 * 404(40400)是正常业务态:报告不存在或已被保留期清理时返回 404。
 * 本函数不抛 40400,而是返回 data=null 的成功包装;其余错误码仍向上抛出。
 *
 * @param id 报告 ID
 * @returns 报告详情;不存在时 data 为 null
 */
export function getHealthReport(id: number): Promise<ApiResponse<AiHealthReport | null>> {
  return apiClient
    .get<ApiResponse<AiHealthReport | null>>(`/ai/health-reports/${id}`, { silent: true })
    .then((r) => r.data)
    .catch((err: unknown) => {
      if (err instanceof ApiBusinessError && err.code === ErrorCode.RESOURCE_NOT_FOUND) {
        return { code: ErrorCode.RESOURCE_NOT_FOUND, message: err.message, data: null }
      }
      throw err
    })
}

/**
 * 调用 POST /api/ai/health-reports/generate 手动触发生成(或重生成)一份报告。
 *
 * 同一 report_date 重复生成走 UPSERT 覆盖旧报告;provider 失败时后端降级为
 * status=degraded 的纯事实报告(HTTP 200),因此该函数只在限流(42906)/
 * 未来日期(40002)/功能未开启(404)等场景抛错。
 *
 * @param req 报告日期;report_date 省略表示由后端取昨日
 * @returns 已落库的报告(可能为 degraded 态)
 */
export function generateHealthReport(
  req: GenerateHealthReportRequest = {}
): Promise<ApiResponse<AiHealthReport>> {
  return apiClient
    .post<ApiResponse<AiHealthReport>>('/ai/health-reports/generate', req, {
      timeout: REPORT_TIMEOUT_MS,
      silent: true
    })
    .then((r) => r.data)
}
