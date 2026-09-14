import apiClient from '@/api/client'
import type { ApiResponse, CurrentUser, LoginResult } from '@/types/api'

/**
 * 用户注册请求体,字段命名与 OpenAPI RegisterRequest schema 对齐。
 */
export interface RegisterRequestBody {
  username: string
  password: string
}

/**
 * 用户登录请求体,字段命名与 OpenAPI LoginRequest schema 对齐。
 */
export interface LoginRequestBody {
  username: string
  password: string
}

/**
 * 调用 POST /api/auth/register 注册用户。
 * 首个注册用户自动成为 admin/approved,后续用户为 user/pending。
 *
 * @param body 注册请求
 * @returns 注册成功的用户数据
 */
export function registerUser(
  body: RegisterRequestBody
): Promise<ApiResponse<CurrentUser>> {
  return apiClient
    .post<ApiResponse<CurrentUser>>('/auth/register', body)
    .then((r) => r.data)
}

/**
 * 调用 POST /api/auth/login 登录。
 * 仅 approved 用户可登录;pending/rejected 用户返回 40300。
 *
 * @param body 登录请求
 * @returns 登录结果,包含 JWT 和当前用户数据
 */
export function loginUser(
  body: LoginRequestBody
): Promise<ApiResponse<LoginResult>> {
  return apiClient
    .post<ApiResponse<LoginResult>>('/auth/login', body)
    .then((r) => r.data)
}

/**
 * 调用 GET /api/auth/me 获取当前登录用户的最新数据库状态。
 * 用于刷新页面时恢复会话并感知状态变更(例如被审核后角色变更)。
 *
 * @returns 当前用户数据
 */
export function getCurrentUser(): Promise<ApiResponse<CurrentUser>> {
  return apiClient.get<ApiResponse<CurrentUser>>('/auth/me').then((r) => r.data)
}

/**
 * 调用 POST /api/auth/logout 完成无状态退出。
 * 服务端不维护黑名单,客户端必须自行删除 token。
 *
 * @returns 成功响应(空 data)
 */
export function logoutUser(): Promise<ApiResponse<null>> {
  return apiClient.post<ApiResponse<null>>('/auth/logout').then((r) => r.data)
}