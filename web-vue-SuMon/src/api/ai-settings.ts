import apiClient, { ApiBusinessError } from '@/api/client'
import { describeAiError } from '@/api/ai'
import { ErrorCode } from '@/types/error-code'
import type {
  AiProviderConfigTestVo,
  AiProviderConfigVo,
  ApiResponse,
  TestAiProviderConfigRequest,
  UpsertAiProviderConfigRequest
} from '@/types/api'

/**
 * 管理员个人 AI 服务商配置 API 封装。
 *
 * 字段命名与 OpenAPI `openapi-ai.json` 的 provider-config 端点严格对齐（snake_case 出站）。
 * 鉴权约束：全部端点后端要求 ROLE_ADMIN（SecurityConfig 显式声明）。
 * 安全约定：api_key 明文仅出现在保存/测试请求中，查询响应只含掩码；
 * 写操作与测试均标记 silent，错误文案由调用方经 describeAiError 映射为中文。
 */
export const EMPTY_API_KEY = ''

/**
 * 调用 GET /api/ai/provider-config 查询当前管理员的个人 AI 配置。
 *
 * @returns 配置视图（configured=false 表示未配置）；后端功能关闭时 404 由调用方降级
 */
export function getMyProviderConfig(): Promise<ApiResponse<AiProviderConfigVo>> {
  return apiClient
    .get<ApiResponse<AiProviderConfigVo>>('/ai/provider-config', { silent: true })
    .then((r) => r.data)
}

/**
 * 调用 PUT /api/ai/provider-config 保存（或覆盖）个人 AI 配置。
 *
 * @param req 配置请求；api_key 省略/空白表示保留已存 Key
 * @returns 保存后的掩码视图
 */
export function saveMyProviderConfig(
  req: UpsertAiProviderConfigRequest
): Promise<ApiResponse<AiProviderConfigVo>> {
  return apiClient
    .put<ApiResponse<AiProviderConfigVo>>('/ai/provider-config', req, { silent: true })
    .then((r) => r.data)
}

/**
 * 调用 DELETE /api/ai/provider-config 删除个人 AI 配置（回退全局配置）。
 *
 * @returns data 为是否实际删除
 */
export function deleteMyProviderConfig(): Promise<ApiResponse<boolean>> {
  return apiClient
    .delete<ApiResponse<boolean>>('/ai/provider-config', { silent: true })
    .then((r) => r.data)
}

/**
 * 调用 POST /api/ai/provider-config/test 发起一次最小真实调用验证连通性。
 *
 * 真实 LLM 往返耗时较长，超时放宽到 60 秒（全局默认 10 秒会误杀）。
 *
 * @param req 测试请求；api_key 空白时服务端复用已存 Key
 * @returns 测试结果（ok=false 时含稳定错误码与可读原因）
 */
export function testMyProviderConfig(
  req: TestAiProviderConfigRequest
): Promise<ApiResponse<AiProviderConfigTestVo>> {
  return apiClient
    .post<ApiResponse<AiProviderConfigTestVo>>('/ai/provider-config/test', req, {
      timeout: 60_000,
      silent: true
    })
    .then((r) => r.data)
}

/** 把 provider-config 相关错误映射为中文文案（复用 AI 模块错误映射表）。 */
export function describeProviderConfigError(err: unknown): string {
  return describeAiError(err, '配置操作失败,请稍后重试')
}

/** 判断错误是否为"AI 功能未开启"（后端 Controller 未装配时的 404）。 */
export function isAiDisabledError(err: unknown): boolean {
  return err instanceof ApiBusinessError && err.code === ErrorCode.RESOURCE_NOT_FOUND
}
