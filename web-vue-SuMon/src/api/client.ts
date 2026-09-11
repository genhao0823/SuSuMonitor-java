import type { AxiosError } from 'axios'
import axios, { type AxiosInstance, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResponse } from '@/types/api'
import { ErrorCode } from '@/types/error-code'

/**
 * 扩展 axios 请求配置:允许单个请求声明 silent,
 * 业务错误时跳过全局 ElMessage 弹窗,由调用方自行处理
 * (例如"告警暂无 AI 解释"的 404 属正常空态,不应弹错误提示)。
 * 40100/40300 的登录态回调不受 silent 影响,始终触发。
 */
declare module 'axios' {
  export interface AxiosRequestConfig {
    silent?: boolean
  }
}

/**
 * 持久化 token 的 localStorage 键名,与 stores/auth.ts 的 persist key 保持一致。
 */
const AUTH_STORAGE_KEY = 'susumonitor-auth'

/**
 * 拦截器外部回调:在 40100 时清空 token 并跳登录,
 * 40300 时跳无权限页;由 main.ts 注入实际跳转逻辑。
 */
export interface ApiClientCallbacks {
  onUnauthorized?: () => void
  onForbidden?: () => void
}

let callbacks: ApiClientCallbacks = {}

/**
 * 注册 axios 客户端的全局回调,用于在错误码触发时执行登录态清理与跳转。
 *
 * @param next 新回调集合;传入 undefined 可清空
 */
export function setApiClientCallbacks(next: ApiClientCallbacks | undefined): void {
  callbacks = next ?? {}
}

/**
 * 生成 UUID v4 字符串,用于 X-Correlation-ID 请求头。
 *
 * 注意:crypto.randomUUID 仅在安全上下文(HTTPS 或 localhost)下可用;
 * 站点以明文 HTTP 提供服务时该方法不存在,直接调用会抛
 * "crypto.randomUUID is not a function",导致请求拦截器在请求发出前失败
 * (表现为点击登录后 network 面板无任何请求)。此处对非安全上下文降级,
 * 与 services/terminal-ws.ts 的兜底策略保持一致。
 *
 * @returns 安全上下文返回标准 UUID v4;否则返回基于时间戳+随机数的降级 ID
 */
export function newCorrelationId(): string {
  return typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

/**
 * 从持久化存储读取当前 JWT。用于请求拦截器注入 Authorization 头。
 * 不直接读 Pinia store 是为了避免 client.ts 与 store 之间的循环依赖。
 *
 * @returns 当前 JWT,无则返回 null
 */
function readStoredToken(): string | null {
  try {
    const raw = localStorage.getItem(AUTH_STORAGE_KEY)
    if (raw === null) {
      return null
    }
    const parsed = JSON.parse(raw) as { token?: unknown }
    if (typeof parsed.token === 'string' && parsed.token.length > 0) {
      return parsed.token
    }
    return null
  } catch {
    return null
  }
}

/**
 * 解析 axios 错误并提取业务错误码;网络错误返回 undefined。
 *
 * @param error axios 抛出的错误对象
 * @returns 业务错误码或 undefined
 */
function extractBusinessCode(error: AxiosError): number | undefined {
  const status = error.response?.status
  if (status === 401) {
    return ErrorCode.UNAUTHORIZED
  }
  if (status === 403) {
    return ErrorCode.FORBIDDEN
  }
  const payload = error.response?.data as ApiResponse<unknown> | undefined
  if (payload && typeof payload.code === 'number') {
    return payload.code
  }
  return undefined
}

/**
 * 统一处理非 2xx 业务响应:触发登录态回调并展示提示。
 *
 * @param code 业务错误码
 * @param message 业务消息
 * @param silent 为 true 时跳过 ElMessage 弹窗(回调不受影响)
 */
function handleBusinessError(code: number, message: string, silent?: boolean): void {
  if (code === ErrorCode.UNAUTHORIZED) {
    callbacks.onUnauthorized?.()
  } else if (code === ErrorCode.FORBIDDEN) {
    callbacks.onForbidden?.()
  }
  if (silent !== true) {
    ElMessage.error(message || '请求失败')
  }
}

/**
 * 项目统一的 axios 实例:所有 HTTP 调用都应通过该实例,
 * 避免在组件内裸用 axios 导致拦截器和错误处理失效。
 */
const apiClient: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 10_000,
  headers: {
    'Content-Type': 'application/json'
  }
})

// 请求拦截器:为每次请求注入 X-Correlation-ID 与 Authorization 头。
apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  if (!config.headers.has('X-Correlation-ID')) {
    config.headers.set('X-Correlation-ID', newCorrelationId())
  }
  const token = readStoredToken()
  if (token !== null && !config.headers.has('Authorization')) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

// 响应拦截器:统一处理业务错误码与网络异常。
apiClient.interceptors.response.use(
  (response) => {
    const payload = response.data as ApiResponse<unknown> | undefined
    if (payload && payload.code !== ErrorCode.SUCCESS) {
      handleBusinessError(payload.code, payload.message, response.config.silent)
      return Promise.reject(new ApiBusinessError(payload.code, payload.message))
    }
    return response
  },
  (error: AxiosError) => {
    const code = extractBusinessCode(error)
    if (code !== undefined) {
      const message =
        (error.response?.data as ApiResponse<unknown> | undefined)?.message ?? error.message
      handleBusinessError(code, message, error.config?.silent)
      return Promise.reject(new ApiBusinessError(code, message))
    }
    ElMessage.error('网络异常,请稍后重试')
    return Promise.reject(error)
  }
)

/**
 * 业务错误对象:携带后端返回的错误码和消息。
 */
export class ApiBusinessError extends Error {
  readonly code: number

  constructor(code: number, message: string) {
    super(message)
    this.code = code
    this.name = 'ApiBusinessError'
  }
}

export default apiClient