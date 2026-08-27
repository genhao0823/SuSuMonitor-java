import apiClient from '@/api/client'
import type {
  AlertNotification,
  AlertPushPayload,
  AlertRecord,
  AlertRecordQuery,
  AlertRule,
  ApiResponse,
  CreateAlertRuleRequest,
  PageResult,
  UpdateAlertRuleRequest
} from '@/types/api'

/**
 * 告警模块 API 封装（MVP-6）。
 *
 * 字段命名与 OpenAPI `openapi-alert.json` 严格对齐（snake_case 出站），
 * 由 `src/types/api.d.ts` 中的类型保证。
 *
 * 鉴权约束：
 *   - `listAlertRules` / `listAlertRecords` / `markAlertRecordAsRead` 任何已认证用户可调。
 *   - `createAlertRule` / `updateAlertRule` / `deleteAlertRule` 后端要求 ROLE_ADMIN。
 *   鉴权失败由 `src/api/client.ts` 统一拦截（40100 / 40300）。
 */

/**
 * 调用 GET /api/alerts/rules 列出全部告警规则。
 *
 * @returns 告警规则数组（按 `created_at` DESC）
 */
export function listAlertRules(): Promise<ApiResponse<AlertRule[]>> {
  return apiClient
    .get<ApiResponse<AlertRule[]>>('/alerts/rules')
    .then((r) => r.data)
}

/**
 * 调用 POST /api/alerts/rules 创建告警规则。
 *
 * @param req 创建请求；`server_id` 省略或 null 表示创建全局规则
 * @returns 创建后的告警规则（含后端写入的 id / 时间戳）
 */
export function createAlertRule(req: CreateAlertRuleRequest): Promise<ApiResponse<AlertRule>> {
  return apiClient
    .post<ApiResponse<AlertRule>>('/alerts/rules', req)
    .then((r) => r.data)
}

/**
 * 调用 PUT /api/alerts/rules/{id} 更新告警规则。
 *
 * 后端禁止修改 `metric` / `operator` / `server_id`；调用方仅传
 * `threshold_value` / `level` / `enabled`，由类型 `UpdateAlertRuleRequest` 保证。
 *
 * @param id 目标规则 ID
 * @param req 更新请求
 * @returns 更新后的告警规则
 */
export function updateAlertRule(
  id: number,
  req: UpdateAlertRuleRequest
): Promise<ApiResponse<AlertRule>> {
  return apiClient
    .put<ApiResponse<AlertRule>>(`/alerts/rules/${id}`, req)
    .then((r) => r.data)
}

/**
 * 调用 DELETE /api/alerts/rules/{id} 软删除告警规则。
 *
 * @param id 目标规则 ID
 * @returns 业务成功响应；`data` 为 null
 */
export function deleteAlertRule(id: number): Promise<ApiResponse<null>> {
  return apiClient
    .delete<ApiResponse<null>>(`/alerts/rules/${id}`)
    .then((r) => r.data)
}

/**
 * 调用 GET /api/alerts/records 分页查询告警记录。
 *
 * @param query 查询参数；`page` / `page_size` 不传则使用后端默认值
 * @returns 分页结果
 */
export function listAlertRecords(
  query: AlertRecordQuery = {}
): Promise<ApiResponse<PageResult<AlertRecord>>> {
  return apiClient
    .get<ApiResponse<PageResult<AlertRecord>>>('/alerts/records', {
      params: {
        page: query.page,
        page_size: query.page_size,
        server_id: query.server_id,
        status: query.status
      }
    })
    .then((r) => r.data)
}

/**
 * 调用 PUT /api/alerts/records/{id}/read 标记指定告警记录为已读。
 *
 * 后端对 `unread` 状态生效；`read` / `resolved` 不受影响。
 *
 * @param id 目标记录 ID
 * @returns 业务成功响应；`data` 为 null
 */
export function markAlertRecordAsRead(id: number): Promise<ApiResponse<null>> {
  return apiClient
    .put<ApiResponse<null>>(`/alerts/records/${id}/read`)
    .then((r) => r.data)
}

/**
 * 调用 GET /api/alerts/records/{id}/notifications 查询某告警记录的
 * 各渠道投递历史（status / attempts / last_error）。
 *
 * @param id 目标记录 ID
 * @returns 通知投递记录数组（按 id 升序）
 */
export function listAlertRecordNotifications(
  id: number
): Promise<ApiResponse<AlertNotification[]>> {
  return apiClient
    .get<ApiResponse<AlertNotification[]>>(`/alerts/records/${id}/notifications`)
    .then((r) => r.data)
}

/**
 * 类型守卫：判断一个未知 `payload` 是否满足 `/ws/monitor` 中
 * `alert.push` 帧的负载结构（见 `docs-SuMon/Protocol-SuMon/websocket-protocol.md`）。
 *
 * 设计为接收"未知 payload"而非整帧，因为 WS 帧分派应在调用前根据
 * `type === 'alert.push'` 完成；本守卫仅承担 payload 形态校验职责。
 *
 * @param payload 任意 WS 帧的 payload
 * @returns 若满足 `AlertPushPayload` 形态则返回 `true`（类型缩窄生效）
 */
export function isAlertPush(payload: unknown): payload is AlertPushPayload {
  if (payload === null || typeof payload !== 'object') return false
  const outer = payload as Record<string, unknown>
  if (typeof outer.server_id !== 'number') return false
  const alert = outer.alert
  if (alert === null || typeof alert !== 'object') return false
  const a = alert as Record<string, unknown>
  return (
    typeof a.id === 'number' &&
    typeof a.metric === 'string' &&
    typeof a.triggered_at === 'string'
  )
}