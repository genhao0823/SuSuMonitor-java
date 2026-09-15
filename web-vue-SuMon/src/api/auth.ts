import apiClient from '@/api/client'
import type { ApiResponse, BootstrapStatus, CurrentUser, LoginResult } from '@/types/api'

/**
 * 用户注册请求体,字段命名与 OpenAPI RegisterRequest schema 对齐。
 * bootstrapToken 仅在首管理员未初始化时必填(缺失 40310/无效 40311),
 * 经服务器启动日志横幅或 AUTH_BOOTSTRAP_TOKEN 获取;首管理员存在后忽略。
 */
export interface RegisterRequestBody {
  username: string
  password: string
  bootstrapToken?: string
}

/**
 * 用户登录请求体,字段命名与 OpenAPI LoginRequest schema 对齐。
 */
export interface LoginRequestBody {
  username: string
  password: string
}

/**
 * 调用 GET /api/auth/bootstrap-status 查询首管理员初始化状态(公开端点)。
 * 注册页/登录页据此决定是否展示一次性初始化令牌输入框与初始化提示。
 * 请求失败时由调用方按 false 降级处理(不阻塞注册表单渲染)。
 *
 * @returns 首管理员初始化状态
 */
export function getBootstrapStatus(): Promise<ApiResponse<BootstrapStatus>> {
  return apiClient.get<ApiResponse<BootstrapStatus>>('/auth/bootstrap-status').then((r) => r.data)
}

/**
 * 调用 POST /api/auth/register 注册用户。
 * 首管理员未初始化时须携带 bootstrapToken,首个用户成为 admin/approved;
 * 后续用户为 user/pending,bootstrapToken 被忽略。
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
 * 请求携带 silent + authAttempt:登录失败属于"本次尝试被拒"而非会话变化,
 * 全局拦截器不弹窗、不触发 onUnauthorized/onForbidden 跳转,
 * 失败文案与去向由 LoginView 统一处理(修复联调观察项:双 Toast 与误跳 /forbidden)。
 *
 * @param body 登录请求
 * @returns 登录结果,包含 JWT 和当前用户数据
 */
export function loginUser(
  body: LoginRequestBody
): Promise<ApiResponse<LoginResult>> {
  return apiClient
    .post<ApiResponse<LoginResult>>('/auth/login', body, { silent: true, authAttempt: true })
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