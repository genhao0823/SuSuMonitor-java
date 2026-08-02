import apiClient from '@/api/client'
import type {
  AdminUserQuery,
  ApiResponse,
  BatchReviewResult,
  CurrentUser,
  PageResult
} from '@/types/api'

/**
 * 管理员审核模块 API 封装。
 * 字段命名与 OpenAPI admin 标签下的 schemas 保持一致。
 */

/**
 * 调用 GET /api/admin/users 分页查询用户列表(支持审核状态筛选与用户名搜索)。
 *
 * @param query 分页/搜索/状态参数(status/keyword/page/page_size)
 * @returns 用户分页结果
 */
export function listUsers(query: AdminUserQuery = {}): Promise<ApiResponse<PageResult<CurrentUser>>> {
  const params: Record<string, string | number> = { page: query.page ?? 1, page_size: query.page_size ?? 20 }
  const keyword = query.keyword?.trim()
  if (keyword !== undefined && keyword.length > 0) {
    params.keyword = keyword
  }
  if (query.status !== undefined && query.status.length > 0) {
    params.status = query.status
  }
  return apiClient
    .get<ApiResponse<PageResult<CurrentUser>>>('/admin/users', { params })
    .then((r) => r.data)
}

/**
 * 调用 PUT /api/admin/users/batch-approve 批量通过待审核用户。
 *
 * @param ids 待审核用户 ID 列表(非空)
 * @returns 处理统计(processed/failed)
 */
export function batchApproveUsers(ids: number[]): Promise<ApiResponse<BatchReviewResult>> {
  return apiClient
    .put<ApiResponse<BatchReviewResult>>('/admin/users/batch-approve', { user_ids: ids })
    .then((r) => r.data)
}

/**
 * 调用 PUT /api/admin/users/batch-reject 批量拒绝待审核用户。
 *
 * @param ids 待审核用户 ID 列表(非空)
 * @returns 处理统计(processed/failed)
 */
export function batchRejectUsers(ids: number[]): Promise<ApiResponse<BatchReviewResult>> {
  return apiClient
    .put<ApiResponse<BatchReviewResult>>('/admin/users/batch-reject', { user_ids: ids })
    .then((r) => r.data)
}

/**
 * 调用 PUT /api/admin/users/{id}/approve 通过指定用户。
 *
 * @param id 目标用户 ID
 * @returns 通过后的用户最新状态
 */
export function approveUser(id: number): Promise<ApiResponse<CurrentUser>> {
  return apiClient
    .put<ApiResponse<CurrentUser>>(`/admin/users/${id}/approve`)
    .then((r) => r.data)
}

/**
 * 调用 PUT /api/admin/users/{id}/reject 拒绝指定用户。
 *
 * @param id 目标用户 ID
 * @returns 拒绝后的用户最新状态
 */
export function rejectUser(id: number): Promise<ApiResponse<CurrentUser>> {
  return apiClient
    .put<ApiResponse<CurrentUser>>(`/admin/users/${id}/reject`)
    .then((r) => r.data)
}