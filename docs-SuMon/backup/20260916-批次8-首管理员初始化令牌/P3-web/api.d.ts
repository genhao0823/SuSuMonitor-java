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
 * 服务器列表查询参数,与后端 OpenAPI 契约保持一致。
 */
export interface ServerQuery {
  page?: number
  page_size?: number
  keyword?: string
  sort_by?: 'id' | 'name' | 'host' | 'status' | 'created_at' | 'updated_at'
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
 * 更新服务器请求体。PUT 为全量基础字段更新：name/host/description/ssh_host/ssh_port/
 * ssh_user/ssh_auth_type 必须完整提交（description 可为空字符串）；凭据字段省略表示
 * 保留原值，切换 ssh_auth_type 时必须提供对应的新主凭据。
 */
export interface UpdateServerRequest {
  name: string
  host: string
  description: string | null
  ssh_host: string
  ssh_port: number
  ssh_user: string
  ssh_auth_type: SshAuthType
  ssh_password?: string
  ssh_private_key?: string
  ssh_private_key_passphrase?: string
}

/**
 * SSH 连接测试结果(与后端 SshTestVo / SshTestHistoryVo 字段对齐)。
 * 单次测试响应 connected 恒为 true;历史记录中 connected=false 时 error_code 非空。
 */
export interface SshTestResult {
  server_id: number
  connected: boolean
  error_code: number | null
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
  /** 告警恢复时间;未恢复时为 null。 */
  resolved_at: string | null
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
  /** 告警恢复时间;未恢复时为 null。 */
  resolved_at: string | null
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
 * 只读观察到的目标主机公钥(与后端 SshHostKeyObservationVo 字段对齐)。
 * 供管理员"一键信任"确认前核对,不涉及登记。
 * registered_fingerprint 为当前已登记指纹,未确认过为 null;与观察指纹不同表示密钥已变更。
 */
export interface SshHostKeyObservation {
  server_id: number
  host_key_algorithm: string
  host_key_fingerprint: string
  registered_fingerprint: string | null
  observed_at: string
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

/* ---------------------------------------------------------------------------
 * AI 模块(/api/ai/** 与 /api/alerts/records/{id}/explanation)类型,
 * 与 openapi-ai.json / openapi-command.json / openapi-alert.json 严格对齐。
 * OpenAPI JSON 是唯一事实源;字段变更必须先改 JSON 再同步本文件。
 * ------------------------------------------------------------------------- */

/**
 * AI 诊断严重程度枚举(与 OpenAPI AiDiagnosis.severity 枚举对齐)。
 */
export type AiSeverity = 'info' | 'warning' | 'critical' | 'unknown'

/**
 * AI 诊断发现置信度枚举(与 OpenAPI AiFinding.confidence 枚举对齐)。
 */
export type AiConfidence = 'low' | 'medium' | 'high' | 'unknown'

/**
 * AI Token 消耗统计(与 OpenAPI AiUsage schema 字段一致)。
 * estimated_cost 为服务端估算值;currency 为三位货币代码。
 */
export interface AiUsage {
  input_tokens: number
  output_tokens: number
  total_tokens: number
  estimated_cost: number
  currency: string
}

/**
 * AI 只读诊断请求体(与 OpenAPI AiDiagnosisRequest 字段一致)。
 * 后端 ignoreUnknown=false,禁止携带契约外字段。
 */
export interface AiDiagnosisRequest {
  server_id: number
  /** 管理员问题,后端视为不可信文本,最长 4000 字符。 */
  question: string
  /** UTC 监控历史窗口(分钟),0~1440。 */
  history_minutes: number
}

/**
 * AI 诊断单条发现(与 OpenAPI AiFinding schema 字段一致)。
 */
export interface AiFinding {
  title: string
  description: string
  confidence: AiConfidence
}

/**
 * AI 诊断单条证据(与 OpenAPI AiEvidence schema 字段一致)。
 * value 为白名单指标的采样值(数字或字符串),可能为 null;
 * 服务端会用真实采样强制覆盖模型输出,前端可直接展示。
 */
export interface AiEvidence {
  metric: string
  value: number | string | null
  observed_at: string
  source: 'monitoring_summary' | 'alert_summary'
}

/**
 * AI 只读诊断结果(与 OpenAPI AiDiagnosis schema 字段一致)。
 * model_used=false 表示后端走了确定性降级摘要(未实际调用大模型)。
 */
export interface AiDiagnosis {
  summary: string
  severity: AiSeverity
  findings: AiFinding[]
  evidence: AiEvidence[]
  recommendations: string[]
  limitations: string[]
  model_used: boolean
  provider: string
  model: string
  prompt_version: string
  usage: AiUsage
}

/**
 * AI 运维问答请求体(与 OpenAPI AiQaRequest 字段一致)。
 * server_id 可省略或传 null,表示全局性问题。
 */
export interface AiQaRequest {
  server_id?: number | null
  /** 管理员问题,最长 4000 字符(后端可能按配置进一步收紧)。 */
  question: string
}

/**
 * 问答本轮实际调用的只读工具审计(与 OpenAPI AiToolCall schema 字段一致)。
 * args 为脱敏后的参数摘要字符串(JSON 文本)。
 */
export interface AiToolCall {
  tool: string
  args: string
}

/**
 * AI 运维问答结果(与 OpenAPI AiQa schema 字段一致)。
 * degraded=true 表示工具化调用失败后回退(无工具单次调用或确定性摘要)。
 */
export interface AiQa {
  answer: string
  tool_calls: AiToolCall[]
  model_used: boolean
  degraded: boolean
  provider: string
  model: string
  prompt_version: string
  usage: AiUsage
}

/**
 * 告警 AI 解释的 Token 消耗(与 openapi-alert.json AiAlertExplanation.usage 内联结构一致;
 * 与 AI 模块的 AiUsage 不同,这里没有 estimated_cost/currency)。
 */
export interface AiExplanationUsage {
  input_tokens: number
  output_tokens: number
  total_tokens: number
}

/**
 * 告警记录 AI 智能解释(与 openapi-alert.json AiAlertExplanation schema 字段一致)。
 * 仅在 susumonitor.ai.explanation.enabled=true 时存在;未生成时接口返回 404。
 */
export interface AiAlertExplanation {
  record_id: number
  summary: string
  possible_causes?: string[]
  impact?: string[]
  suggestions?: string[]
  limitations?: string[]
  usage?: AiExplanationUsage | null
  provider: string
  model: string
  prompt_version: string
  created_at?: string | null
}

/**
 * AI 命令建议请求体(与 OpenAPI CommandSuggestionRequest 字段一致)。
 * intent 为运维意图描述,最长 2000 字符。
 */
export interface CommandSuggestionRequest {
  server_id: number
  intent: string
}

/**
 * 手动基于白名单模板创建待审批命令请求体(与 OpenAPI ManualCommandRequest 字段一致)。
 * params 键值对最多 16 个,值必须满足模板参数正则,否则后端 40004。
 */
export interface ManualCommandRequest {
  server_id: number
  template_id: string
  params?: Record<string, string>
}

/**
 * 命令运行状态枚举(与 OpenAPI CommandRun.status 枚举对齐)。
 * 终态:succeeded / failed / rejected / expired / timeout;其余状态会继续流转。
 */
export type CommandRunStatus =
  | 'pending_approval'
  | 'approved'
  | 'executing'
  | 'succeeded'
  | 'failed'
  | 'rejected'
  | 'expired'
  | 'timeout'

/**
 * 命令运行来源枚举(与 OpenAPI CommandRun.source 枚举对齐)。
 */
export type CommandRunSource = 'ai' | 'manual'

/**
 * 命令风险等级(与 OpenAPI CommandRun.risk_level 枚举对齐)。
 * low=只读诊断;medium=低影响变更(预留);high=高影响变更(永不自动审批)。
 */
export type CommandRiskLevel = 'low' | 'medium' | 'high'

/**
 * 审批方式(与 OpenAPI CommandRun.approval_mode 枚举对齐)。
 * manual=人工审批;auto=策略自动审批(approver_id 为空)。
 */
export type CommandApprovalMode = 'manual' | 'auto'

/**
 * AI 命令建议元信息(与 OpenAPI CommandRun.proposal 内联结构一致)。
 * source=manual 时整个 proposal 为 null。
 */
export interface CommandProposal {
  reason?: string
  model?: string
  prompt_version?: string
}

/**
 * 命令执行输出(与 OpenAPI CommandRun.result 内联结构一致)。
 * 为 Agent 回传的脱敏截断文本;未执行完成前为 null。
 */
export interface CommandRunResult {
  stdout?: string
  stderr?: string
  truncated?: boolean
  error?: string
}

/**
 * 命令运行记录(与 OpenAPI CommandRun schema 字段一致)。
 * rendered_command 是审批人确认执行内容的唯一依据。
 */
export interface CommandRun {
  id: number
  execution_id: string
  server_id: number
  template_id: string
  params?: Record<string, string>
  rendered_command: string
  status: CommandRunStatus
  source: CommandRunSource
  risk_level?: CommandRiskLevel
  approval_mode?: CommandApprovalMode
  proposal?: CommandProposal | null
  result?: CommandRunResult | null
  exit_code?: number | null
  proposer_id: number
  approver_id?: number | null
  expires_at?: string | null
  created_at: string
  completed_at?: string | null
}

/**
 * 命令运行分页查询参数(与 OpenAPI listCommandRuns parameters 对齐)。
 * server_id / status 不传表示不过滤;page_size 范围 1~100。
 */
export interface CommandRunQuery {
  server_id?: number
  status?: CommandRunStatus
  page?: number
  page_size?: number
}

/**
 * 白名单命令模板参数规则(与 OpenAPI TemplateListResponse items.params 字段一致)。
 * pattern 为服务端冻结的正则,前端用它做输入框预校验(后端仍会严格拦截)。
 */
export interface CommandTemplateParam {
  name: string
  pattern: string
}

/**
 * 白名单命令模板(与 OpenAPI TemplateListResponse items 字段一致)。
 * argv 为命令二进制与固定参数列表,不含用户输入。
 */
export interface CommandTemplate {
  id: string
  argv: string[]
  risk_level?: CommandRiskLevel
  params: CommandTemplateParam[]
}

/**
 * 自动审批策略快照(与 OpenAPI AutoApprovalPolicyResponse 字段一致)。
 * enabled=false 时所有命令仍走人工审批;max_risk_level 为阈值(low/medium)。
 */
export interface AutoApprovalPolicy {
  enabled: boolean
  max_risk_level: CommandRiskLevel
  updated_at?: string | null
  updated_by?: number | null
}

/**
 * 更新自动审批策略请求体(与 OpenAPI AutoApprovalPolicyRequest 字段一致)。
 */
export interface AutoApprovalPolicyRequest {
  enabled: boolean
  max_risk_level: CommandRiskLevel
}

/**
 * 观察期评审报告单条基线核对项(与 OpenAPI ObservationCriterion 一致)。
 * passed 三态:true/false 判定,null 表示样本不足无法评判。
 */
export interface ObservationCriterion {
  key: 'sample_size' | 'auto_failure_rate' | 'timeout_rate' | 'high_risk_auto' | 'expired_rate'
  value?: number | null
  threshold: string
  passed?: boolean | null
}

/**
 * 观察期评审报告模板用量条目(与 OpenAPI TemplateUsageItem 一致)。
 */
export interface TemplateUsageItem {
  template_id: string
  runs: number
}

/**
 * 报告携带的当前自动审批策略快照(与 OpenAPI ObservationPolicySnapshot 一致)。
 * note 为 v1 边界说明:窗口内策略变更未追踪。
 */
export interface ObservationPolicySnapshot {
  enabled: boolean
  max_risk_level: CommandRiskLevel
  updated_at?: string | null
  updated_by?: number | null
  note?: string | null
}

/**
 * M2 观察期评审报告(与 OpenAPI CommandObservationReport 一致)。
 * by_* 分布仅含非零项;overall: pass/fail/insufficient(样本不足)。
 */
export interface CommandObservationReport {
  window_days: number
  window_start: string
  window_end: string
  total_runs: number
  by_status?: Record<string, number>
  by_approval_mode?: Record<string, number>
  by_risk_level?: Record<string, number>
  by_source?: Record<string, number>
  auto_executed?: number
  auto_failed?: number
  manual_executed?: number
  manual_failed?: number
  distinct_servers?: number
  avg_duration_ms?: number | null
  max_duration_ms?: number | null
  template_usage?: TemplateUsageItem[]
  policy?: ObservationPolicySnapshot
  criteria: ObservationCriterion[]
  overall: 'pass' | 'fail' | 'insufficient'
}

/**
 * 管理员个人 AI 服务商配置视图(与 OpenAPI AiProviderConfigView 字段一致)。
 * api_key 只返回掩码,明文永远不离开服务端。
 */
export interface AiProviderConfigVo {
  configured: boolean
  provider?: string | null
  base_url?: string | null
  model?: string | null
  api_key_masked?: string | null
  enabled?: boolean | null
  updated_at?: string | null
}

/**
 * 保存个人 AI 服务商配置请求体(与 OpenAPI UpsertAiProviderConfigRequest 一致)。
 * api_key 省略或空白表示保留已存 Key;endpoint 默认强制 HTTPS。
 */
export interface UpsertAiProviderConfigRequest {
  base_url: string
  api_key?: string | null
  model: string
  enabled?: boolean
}

/**
 * 个人 AI 服务商连通性测试请求体(与 OpenAPI TestAiProviderConfigRequest 一致)。
 * api_key 空白时服务端复用已存 Key。
 */
export interface TestAiProviderConfigRequest {
  base_url: string
  api_key?: string | null
  model: string
}

/** 连通性测试结果(与 OpenAPI AiProviderConfigTestResult 一致);ok=false 时给出稳定错误码。 */
export interface AiProviderConfigTestVo {
  ok: boolean
  latency_ms: number
  error_code?: number | null
  message?: string | null
}

/**
 * 定时健康报告生成状态(与 OpenAPI AiHealthReport.status 枚举一致)。
 * degraded=纯聚合事实报告(模型摘要缺席),summary 为空并附带 error_code。
 */
export type AiHealthReportStatus = 'succeeded' | 'degraded'

/**
 * 报告覆盖窗口内的服务器清单快照(与 OpenAPI AiHealthReportFacts.server_inventory 一致)。
 * online_rate 为百分比(一位小数);离线清单为生成时刻快照而非掉线事件流水。
 */
export interface AiHealthReportServerInventory {
  total_count: number
  online_count: number
  offline_count: number
  online_rate: number
}

/**
 * 报告窗口内的指标均值与峰值(与 OpenAPI AiHealthReportFacts.metric_peaks 一致)。
 * 窗口内无采样时各值为 null;峰值附带所属服务器 ID 供定位。
 */
export interface AiHealthReportMetricPeaks {
  avg_cpu_percent: number | null
  max_cpu_percent: number | null
  max_cpu_server_id: number | null
  avg_memory_percent: number | null
  max_memory_percent: number | null
  max_memory_server_id: number | null
  avg_disk_percent: number | null
  max_disk_percent: number | null
  max_disk_server_id: number | null
}

/**
 * 报告窗口内告警最多的服务器(与 OpenAPI AiHealthReportFacts.top_servers items 一致)。
 */
export interface AiHealthReportTopAlertServer {
  server_id: number
  server_name: string
  alert_count: number
  critical_count: number
}

/**
 * 报告窗口内的告警统计(与 OpenAPI AiHealthReportFacts.alert_statistics 一致)。
 */
export interface AiHealthReportAlertStatistics {
  total_triggered: number
  critical_count: number
  warning_count: number
  resolved_count: number
  unresolved_count: number
  top_servers: AiHealthReportTopAlertServer[]
}

/**
 * 当前离线服务器快照行(与 OpenAPI AiHealthReportFacts.offline_servers items 一致)。
 */
export interface AiHealthReportOfflineServer {
  server_id: number
  server_name: string
  agent_status: string
  last_heartbeat_at: string | null
}

/**
 * 服务端聚合的白名单事实快照(与 OpenAPI AiHealthReportFacts 一致)。
 * 事实数据以本快照为准,模型文本仅作解读。
 */
export interface AiHealthReportFacts {
  report_date: string
  server_inventory: AiHealthReportServerInventory
  metric_peaks: AiHealthReportMetricPeaks
  alert_statistics: AiHealthReportAlertStatistics
  offline_servers: AiHealthReportOfflineServer[]
}

/**
 * 定时健康报告(与 OpenAPI AiHealthReport schema 字段一致,F3/V34)。
 * status=degraded 时 summary 为 null 且 error_code 记录降级原因(如 42906);
 * result_json 损坏的降级回退路径下 facts 也可能为 null(回看仅剩结构化列)。
 */
export interface AiHealthReport {
  id: number
  report_date: string
  status: AiHealthReportStatus
  provider: string
  model: string
  prompt_version: string
  summary: string | null
  top_concerns: string[]
  limitations: string[]
  facts: AiHealthReportFacts | null
  error_code: number | null
  usage: AiUsage
  duration_ms: number
  created_at: string
}

/**
 * 手动触发健康报告生成请求体(与 OpenAPI GenerateHealthReportRequest 一致)。
 * report_date 省略表示由后端取昨日;不可晚于当日,否则 40002。
 */
export interface GenerateHealthReportRequest {
  report_date?: string
}