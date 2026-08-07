/**
 * 项目级 API 类型定义。
 *
 * 字段命名规则:
 * - 认证模块(/api/auth/**)响应字段与 OpenAPI 一致,使用 camelCase。
 * - 服务器模块(/api/servers/**)响应字段与 OpenAPI 一致,使用 snake_case。
 * OpenAPI JSON 是唯一事实源,字段变更必须先改 JSON 再同步本文件。
 */

/**
 * 项目统一 API 响应包装。
 */
export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

/**
 * 统一分页结构(/api/servers 列表)。
 */
export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  page_size: number
}

/**
 * /api/health 响应数据。
 */
export interface HealthStatus {
  status: string
  application: string
  timestamp: string
}

/**
 * /api/ready 响应数据。
 */
export interface ReadyStatus {
  status: string
  database: string
  timestamp: string
}

/**
 * 用户角色枚举。
 */
export type UserRole = 'admin' | 'user'

/**
 * 用户审核状态枚举。
 */
export type ReviewStatus = 'pending' | 'approved' | 'rejected'

/**
 * 当前用户数据(与 OpenAPI CurrentUser schema 字段一致)。
 */
export interface CurrentUser {
  id: number
  username: string
  role: UserRole
  reviewStatus: ReviewStatus
  reviewedAt: string | null
  createdAt: string
}

/**
 * 管理员用户列表分页查询参数(与 OpenAPI listUsers parameters 对齐)。
 */
export interface AdminUserQuery {
  /** 审核状态筛选(可选):pending/approved/rejected,不传不过滤。 */
  status?: 'pending' | 'approved' | 'rejected'
  /** 用户名模糊关键字(可选)。 */
  keyword?: string
  /** 页码,从 1 起,默认 1。 */
  page?: number
  /** 每页大小 1~100,默认 20。 */
  page_size?: number
}

/**
 * 批量审核结果(与 OpenAPI BatchReviewResult schema 字段一致)。
 */
export interface BatchReviewResult {
  /** 成功审核的用户数。 */
  processed: number
  /** 失败的用户数(状态已变化/不存在/参数非法)。 */
  failed: number
  /** 失败的用户 ID 列表(前端可映射用户名展示明细)。 */
  failed_ids: number[]
}

/**
 * 登录结果(与 OpenAPI LoginResult schema 字段一致)。
 */
export interface LoginResult {
  token: string
  tokenType: string
  expiresIn: number
  user: CurrentUser
}

/**
 * 服务器状态枚举。
 */
export type ServerStatusKind = 'online' | 'offline' | 'unknown'

/**
 * Agent 状态枚举。
 */
export type AgentStatusKind = 'online' | 'offline'

/**
 * SSH 认证方式枚举。
 */
export type SshAuthType = 'password' | 'private_key'

/**
 * 服务器公开 VO,不包含凭据明文或密文。
 */
export interface Server {
  id: number
  name: string
  host: string
  description: string | null
  status: ServerStatusKind
  ssh_host: string
  ssh_port: number
  ssh_user: string
  ssh_auth_type: SshAuthType
  agent_id: string | null
  agent_status: AgentStatusKind
  last_heartbeat_at: string | null
  created_at: string
  updated_at: string
}

/**
 * 服务器状态快照。
 */
export interface ServerStatus {
  server_id: number
  status: ServerStatusKind
  agent_status: AgentStatusKind
  last_heartbeat_at: string | null
  delivery_pending_count: number | null
  delivery_pending_bytes: number | null
  delivery_oldest_collected_at: string | null
  delivery_drop_count: number | null
  delivery_dead_letter_count: number | null
  delivery_dead_letter_bytes: number | null
  checked_at: string
}

/**
 * Monitor WebSocket 推送的服务器状态转换快照。
 */
export interface ServerStatusPushPayload {
  server_id: number
  status: ServerStatusKind
  agent_status: AgentStatusKind
  last_heartbeat_at: string | null
}

/**
 * 服务器列表查询参数。
 * 后端 OpenAPI 允许 keyword 单一字段模糊匹配;前端额外提供 name/host
 * 两个独立字段以满足"按 name 搜"与"按 host 搜"的精确场景。
 */
export interface ServerQuery {
  page?: number
  page_size?: number
  keyword?: string
  name?: string
  host?: string
  sort_by?: 'id' | 'name' | 'host' | 'created_at' | 'updated_at'
  sort_order?: 'asc' | 'desc'
}

/**
 * 创建服务器请求体,字段命名与 OpenAPI CreateServerRequest schema 对齐。
 * 凭据字段按 ssh_auth_type 二选一,空字符串或省略表示不修改/不设置。
 */
export interface CreateServerRequest {
  name: string
  host: string
  description?: string | null
  ssh_host: string
  ssh_port: number
  ssh_user: string
  ssh_auth_type: SshAuthType
  ssh_password?: string
  ssh_private_key?: string
  ssh_private_key_passphrase?: string
}

/**
 * 更新服务器请求体。所有字段可选;省略的字段后端保留原值。
 * 凭据字段在编辑模式下若保持空字符串则后端不更新该凭据(由调用方过滤)。
 */
export interface UpdateServerRequest {
  name?: string
  host?: string
  description?: string | null
  ssh_host?: string
  ssh_port?: number
  ssh_user?: string
  ssh_auth_type?: SshAuthType
  ssh_password?: string
  ssh_private_key?: string
  ssh_private_key_passphrase?: string
}

/**
 * SSH 连接测试结果(与后端 SshTestVo 字段对齐)。
 */
export interface SshTestResult {
  server_id: number
  connected: boolean
  host_key_algorithm: string | null
  host_key_fingerprint: string | null
  auth_type: string
  duration_ms: number
  tested_at: string
}

/* ---------------------------------------------------------------------------
 * 告警模块(/api/alerts/**)类型,与 openapi-alert.json 严格对齐。
 * OpenAPI JSON 是唯一事实源;字段变更必须先改 JSON 再同步本文件。
 * ------------------------------------------------------------------------- */

/**
 * 告警指标枚举(与 OpenAPI CreateAlertRuleRequest.metric 枚举对齐)。
 * 后端当前支持 cpu/memory/disk/temperature/load;新增指标需同步 OpenAPI。
 */
export type AlertMetric = 'cpu' | 'memory' | 'disk' | 'temperature' | 'load'

/**
 * 告警比较运算符枚举(与 OpenAPI CreateAlertRuleRequest.operator 枚举对齐)。
 */
export type AlertOperator = '>' | '>=' | '<' | '<='

/**
 * 告警等级枚举(与 OpenAPI AlertRule.level 枚举对齐)。
 */
export type AlertLevel = 'warning' | 'critical'

/**
 * 告警记录状态枚举(与 OpenAPI AlertRecord.status 枚举对齐)。
 * unread = 未处理;read = 已读;resolved = 已恢复/已解决。
 */
export type AlertStatus = 'unread' | 'read' | 'resolved'

/**
 * 告警规则(与 OpenAPI AlertRule schema 字段一致)。
 * server_id 为 null 表示全局规则,匹配所有服务器。
 */
export interface AlertRule {
  id: number
  server_id: number | null
  metric: AlertMetric | string
  operator: AlertOperator | string
  threshold_value: number
  level: AlertLevel | string
  /** 连续越界确认次数: 1=立即触发,>1=连续N次越界触发(逃逸窗口)。 */
  confirm_count: number
  /** 通知邮件地址,多个用英文逗号分隔;为空不发送。 */
  notify_email: string | null
  /** 钉钉机器人 Webhook URL;为空不发送。 */
  notify_dingtalk: string | null
  /** 自定义 Webhook URL;为空不发送。 */
  notify_webhook: string | null
  enabled: boolean
  created_by: number | null
  created_at: string
  updated_at: string
}

/**
 * 告警记录(与 OpenAPI AlertRecord schema 字段一致)。
 * 后端按 triggered_at DESC 返回;前端按需展示。
 */
export interface AlertRecord {
  id: number
  rule_id: number | null
  server_id: number
  metric: AlertMetric | string
  current_value: number
  threshold_value: number
  level: AlertLevel | string
  status: AlertStatus
  message: string | null
  read_by: number | null
  read_at: string | null
  triggered_at: string
  /** 外部通知发送完成时间;null=未成功发送。 */
  notified_at: string | null
  /** 成功送达的渠道,逗号分隔(email/dingtalk/webhook);null=未成功发送。 */
  notify_channels: string | null
  created_at: string
}

/**
 * /api/alerts/records/{id}/notifications 返回的单条渠道投递记录。
 *
 * status: pending(待发送/待重试) / sent(已送达) / failed(达重试上限放弃)。
 * next_attempt_at 为 null 表示不再重试;last_error 记录最近失败原因。
 */
export interface AlertNotification {
  id: number
  alert_record_id: number
  channel: 'email' | 'dingtalk' | 'webhook' | string
  status: 'pending' | 'sent' | 'failed' | string
  attempts: number
  next_attempt_at: string | null
  last_error: string | null
  created_at: string | null
  updated_at: string | null
}

/**
 * /ws/monitor alert.push payload 中内嵌的简化告警对象。
 *
 * 注意:与完整 AlertRecord 不同,push 不承诺携带 message/read_by/read_at/created_at,
 * 也不包含外层的 server_id(server_id 在 payload 顶层)。前端不能把 push 当作 REST
 * 记录直接合入列表,只能用作增量提示;最终展示应以 REST 拉取为准。
 */
export interface AlertPushAlert {
  id: number
  rule_id: number | null
  metric: AlertMetric | string
  current_value: number
  threshold_value: number
  level: AlertLevel | string
  status: AlertStatus
  triggered_at: string
}

/**
 * /ws/monitor alert.push payload 顶层结构(对齐 websocket-protocol.md §Alert Push)。
 */
export interface AlertPushPayload {
  server_id: number
  alert: AlertPushAlert
}

/**
 * 创建告警规则请求体(与 OpenAPI CreateAlertRuleRequest 字段一致)。
 * server_id 可省略或显式传 null,表示创建全局规则。
 */
export interface CreateAlertRuleRequest {
  server_id?: number | null
  metric: AlertMetric
  operator: AlertOperator
  threshold_value: number
  level: AlertLevel
  /** 连续越界确认次数(可选,默认1=立即触发)。 */
  confirm_count?: number
  /** 通知邮件地址,多个用英文逗号分隔(可选)。 */
  notify_email?: string | null
  /** 钉钉机器人 Webhook URL(可选)。 */
  notify_dingtalk?: string | null
  /** 自定义 Webhook URL(可选)。 */
  notify_webhook?: string | null
}

/**
 * 更新告警规则请求体(与 OpenAPI UpdateAlertRuleRequest 字段一致)。
 * 后端禁止修改 metric/operator/server_id,前端编辑表单也只允许这三个字段。
 */
export interface UpdateAlertRuleRequest {
  threshold_value: number
  level: AlertLevel
  enabled: boolean
  /** 连续越界确认次数(可选项,不传保持原值)。 */
  confirm_count?: number
  /** 通知邮件地址(传入则更新,不传保持原值)。 */
  notify_email?: string | null
  /** 钉钉机器人 Webhook URL(传入则更新,不传保持原值)。 */
  notify_dingtalk?: string | null
  /** 自定义 Webhook URL(传入则更新,不传保持原值)。 */
  notify_webhook?: string | null
}

/**
 * 告警记录分页查询参数(与 OpenAPI listAlertRecords parameters 对齐)。
 * status 不传表示全部;传入时必须为 AlertStatus 枚举值。
 */
export interface AlertRecordQuery {
  page?: number
  page_size?: number
  server_id?: number
  status?: AlertStatus
}

/* ---------------------------------------------------------------------------
 * SSH 主机指纹 + Agent Token(/api/servers/{id}/ssh/host-key 与
 * /api/servers/{id}/agent/{register,rotate,revoke})类型。
 * OpenAPI JSON 是唯一事实源;字段变更必须先改 JSON 再同步本文件。
 * ------------------------------------------------------------------------- */

/**
 * SSH 主机公钥确认结果(与 OpenAPI SshHostKeyResult schema 字段一致)。
 * operation 区分首次确认、显式轮换与幂等复核。
 */
export interface SshHostKey {
  server_id: number
  host_key_algorithm: string
  host_key_fingerprint: string
  operation: 'confirmed' | 'rotated' | 'unchanged'
  verified_at: string
}

/**
 * 主机指纹确认 / 轮换请求体(与 OpenAPI ConfirmSshHostKeyRequest 对齐)。
 * replace 默认 false;管理员仅在已验证带外新指纹后置 true。
 */
export interface ConfirmSshHostKeyRequest {
  expected_fingerprint: string
  replace?: boolean
}

/**
 * Agent Token 一次性返回值(与 OpenAPI AgentTokenResult schema 字段一致)。
 * agent_token 仅在 register / rotate 成功响应中出现一次;revoke 返回 null data。
 * 前端不得把 agent_token 写入 store / localStorage / 日志。
 */
export interface AgentToken {
  server_id: number
  agent_token: string
  created_at: string
}