import apiClient, { ApiBusinessError } from '@/api/client'
import { ErrorCode } from '@/types/error-code'
import type {
  AiAlertExplanation,
  AiDiagnosis,
  AiDiagnosisRequest,
  AiQa,
  AiQaRequest,
  ApiResponse
} from '@/types/api'

/**
 * AI 模块 API 封装(只读诊断 / 运维问答 / 告警智能解释)。
 *
 * 字段命名与 OpenAPI `openapi-ai.json`、`openapi-alert.json` 严格对齐(snake_case 出站),
 * 由 `src/types/api.d.ts` 中的类型保证。
 *
 * 鉴权约束:三个接口后端均要求 ROLE_ADMIN(SecurityConfig 显式声明),
 * 鉴权失败由 `src/api/client.ts` 统一拦截(40100 / 40300)。
 *
 * 静默约定:AI 接口全部标记 `silent: true`,不触发全局 ElMessage;
 * 错误文案由调用方通过 {@link describeAiError} 映射为中文提示,
 * 避免"resource not found"等英文原文直接暴露给用户。
 *
 * 功能开关:后端各 AI 开关(AI_ENABLED / AI_QA_ENABLED / AI_EXPLANATION_ENABLED)
 * 默认关闭,关闭时对应 Controller 不装配,请求得到 404(40400);
 * 调用方应把 40400 视为"功能未开启"而非异常。
 */

/**
 * AI 大模型调用的前端超时上限(毫秒)。
 *
 * 全局 axios 实例超时为 10 秒,面向普通 CRUD;而诊断/问答在服务端
 * 可能经历多次 LLM 往返与有界重试(连接 5s + 读取 30s × 最多 2 次),
 * 10 秒必然误杀。此处放宽到 120 秒,覆盖服务端最坏路径。
 */
const AI_TIMEOUT_MS = 120_000

/**
 * 调用 POST /api/ai/diagnoses 生成只读 AI 诊断。
 *
 * 后端在 provider 故障时返回 HTTP 200 + model_used=false 的确定性降级摘要,
 * 因此该函数只在参数/权限/限流/未开启等场景抛错。
 *
 * @param req 诊断请求(server_id + question + history_minutes 均必填)
 * @returns 结构化诊断结果
 */
export function requestAiDiagnosis(req: AiDiagnosisRequest): Promise<ApiResponse<AiDiagnosis>> {
  return apiClient
    .post<ApiResponse<AiDiagnosis>>('/ai/diagnoses', req, {
      timeout: AI_TIMEOUT_MS,
      silent: true
    })
    .then((r) => r.data)
}

/**
 * 调用 POST /api/ai/qa 进行工具化运维问答(单轮)。
 *
 * 后端为无状态单轮接口;会话式展示由前端自行维护。
 * degraded=true 表示工具化调用失败后发生了回退,answer 仍可用。
 *
 * @param req 问答请求(question 必填;server_id 省略表示全局提问)
 * @returns 回答与工具调用审计
 */
export function requestAiQa(req: AiQaRequest): Promise<ApiResponse<AiQa>> {
  return apiClient
    .post<ApiResponse<AiQa>>('/ai/qa', req, { timeout: AI_TIMEOUT_MS, silent: true })
    .then((r) => r.data)
}

/**
 * 调用 GET /api/alerts/records/{id}/explanation 回看告警的 AI 智能解释。
 *
 * 404(40400)是正常业务态:解释尚未生成、生成失败或功能未开启时均返回 404。
 * 本函数不抛 40400,而是返回 data=null 的成功包装,便于调用方直接渲染空态;
 * 其余错误码(40100/40300 等)仍向上抛出。
 *
 * @param id 目标告警记录 ID
 * @returns 解释内容;暂无解释时 data 为 null
 */
export function getAlertExplanation(id: number): Promise<ApiResponse<AiAlertExplanation | null>> {
  return apiClient
    .get<ApiResponse<AiAlertExplanation | null>>(`/alerts/records/${id}/explanation`, {
      silent: true
    })
    .then((r) => r.data)
    .catch((err: unknown) => {
      if (err instanceof ApiBusinessError && err.code === ErrorCode.RESOURCE_NOT_FOUND) {
        return { code: ErrorCode.RESOURCE_NOT_FOUND, message: err.message, data: null }
      }
      throw err
    })
}

/**
 * 把 AI 相关请求抛出的错误映射为面向管理员的中文提示文案。
 *
 * 映射范围覆盖 AI 模块与命令域的全部业务错误码(与 ErrorCode.java 对齐);
 * 未识别的 ApiBusinessError 使用后端原始 message,非业务错误(网络层)使用通用文案。
 *
 * @param err catch 捕获到的未知错误
 * @param fallback 兜底文案(默认"AI 请求失败,请稍后重试")
 * @returns 可直接用于 ElMessage / 页面展示的中文文案
 */
export function describeAiError(err: unknown, fallback = 'AI 请求失败,请稍后重试'): string {
  if (!(err instanceof ApiBusinessError)) {
    return '网络异常,请稍后重试'
  }
  switch (err.code) {
    case ErrorCode.FORBIDDEN:
      return '没有权限执行该操作,需要管理员角色'
    case ErrorCode.RESOURCE_NOT_FOUND:
      return 'AI 功能未开启或目标资源不存在'
    case ErrorCode.COMMAND_PARAM_INVALID:
      return '命令参数不符合模板白名单要求'
    case ErrorCode.COMMAND_RUN_NOT_FOUND:
      return '命令运行记录不存在或已被清理'
    case ErrorCode.COMMAND_RUN_STATE_CONFLICT:
      return '记录状态已变更或已过期,请刷新列表'
    case ErrorCode.COMMAND_AGENT_OFFLINE:
      return '目标服务器 Agent 离线,命令无法下发(记录已标记为通过)'
    case ErrorCode.AI_RATE_LIMIT_REACHED:
    case ErrorCode.COMMAND_RATE_LIMIT_REACHED:
      return 'AI 请求过于频繁或今日额度已用尽,请稍后再试'
    case ErrorCode.AI_PROVIDER_UNAVAILABLE:
      return 'AI 服务商暂时不可用,请稍后再试'
    case ErrorCode.AI_DISABLED_OR_REDACTION_FAILED:
      return 'AI 功能未开启或服务端配置不完整,请联系管理员'
    case ErrorCode.AI_RESPONSE_INVALID:
      return 'AI 返回内容未通过校验,请重试'
    case ErrorCode.AI_PROVIDER_TIMEOUT:
      return 'AI 服务响应超时,请稍后再试'
    case ErrorCode.COMMAND_EXECUTION_TIMEOUT:
      return '命令执行超时,请查看运行记录确认实际状态'
    default:
      return err.message || fallback
  }
}
