import apiClient from '@/api/client'
import type {
  ApiResponse,
  AutoApprovalPolicy,
  AutoApprovalPolicyRequest,
  CommandRun,
  CommandRunQuery,
  CommandSuggestionRequest,
  CommandTemplate,
  ManualCommandRequest,
  PageResult
} from '@/types/api'

/**
 * AI 命令域(M1 审批制)API 封装。
 *
 * 字段命名与 OpenAPI `openapi-command.json` 严格对齐(snake_case 出站),
 * 由 `src/types/api.d.ts` 中的类型保证。
 *
 * 鉴权约束:全部端点后端要求 ROLE_ADMIN(SecurityConfig 显式声明),
 * 鉴权失败由 `src/api/client.ts` 统一拦截(40100 / 40300)。
 *
 * 静默约定:写操作与 AI 建议均标记 `silent: true`,不触发全局 ElMessage;
 * 错误文案由调用方通过 `@/api/ai` 的 {@link describeAiError} 映射为中文提示。
 *
 * 安全约束:本模块只提供白名单模板的建议 / 手动创建 / 审批入口;
 * 不存在也不允许出现自由命令文本执行。所有创建仅产生 pending_approval
 * 记录,approve 后由服务端经 Agent 通道下发,前端通过轮询获取结果。
 */

/**
 * AI 建议类请求与普通 CRUD 不同的超时上限(毫秒),理由见 `@/api/ai` 的 AI_TIMEOUT_MS。
 * 轮询与审批等普通请求沿用全局 10 秒超时即可。
 */
const AI_SUGGESTION_TIMEOUT_MS = 120_000

/**
 * 调用 POST /api/ai/commands/suggestions:AI 根据运维意图建议白名单命令。
 *
 * 返回的每条建议都已经是 pending_approval 状态的运行记录
 * (AI 仅输出模板 ID 与参数,服务端渲染并校验;全部建议非法时返回空数组)。
 *
 * @param req 建议请求(server_id + intent 均必填)
 * @returns 新建的待审批运行记录数组
 */
export function requestCommandSuggestions(
  req: CommandSuggestionRequest
): Promise<ApiResponse<CommandRun[]>> {
  return apiClient
    .post<ApiResponse<CommandRun[]>>('/ai/commands/suggestions', req, {
      timeout: AI_SUGGESTION_TIMEOUT_MS,
      silent: true
    })
    .then((r) => r.data)
}

/**
 * 调用 POST /api/ai/commands/runs:基于白名单模板手动创建待审批运行记录。
 *
 * @param req 手动创建请求(template_id 必填;params 键值对最多 16 个)
 * @returns 新建的待审批运行记录(含渲染后的命令预览)
 */
export function createManualCommandRun(
  req: ManualCommandRequest
): Promise<ApiResponse<CommandRun>> {
  return apiClient
    .post<ApiResponse<CommandRun>>('/ai/commands/runs', req, { silent: true })
    .then((r) => r.data)
}

/**
 * 调用 GET /api/ai/commands/runs 分页查询命令运行记录。
 *
 * @param query 查询参数;server_id / status 不传表示不过滤
 * @returns 分页结果(按 created_at 倒序)
 */
export function listCommandRuns(
  query: CommandRunQuery = {}
): Promise<ApiResponse<PageResult<CommandRun>>> {
  return apiClient
    .get<ApiResponse<PageResult<CommandRun>>>('/ai/commands/runs', {
      params: {
        server_id: query.server_id,
        status: query.status,
        page: query.page,
        page_size: query.page_size
      }
    })
    .then((r) => r.data)
}

/**
 * 调用 GET /api/ai/commands/runs/{id} 查询单条运行记录。
 * 审批通过后的执行进度(执行中/成功/失败)依赖本接口轮询获取。
 *
 * @param id 运行记录 ID
 * @returns 运行记录详情
 */
export function getCommandRun(id: number): Promise<ApiResponse<CommandRun>> {
  return apiClient
    .get<ApiResponse<CommandRun>>(`/ai/commands/runs/${id}`, { silent: true })
    .then((r) => r.data)
}

/**
 * 调用 POST /api/ai/commands/runs/{id}/approve 审批通过并下发到 Agent。
 *
 * 请求体为空(后端不读取 body)。Agent 离线时记录仍会推进为 approved,
 * 但后端返回 40906;40905 表示记录已非待审批或已过期。
 *
 * @param id 运行记录 ID
 * @returns 审批后的最新记录状态
 */
export function approveCommandRun(id: number): Promise<ApiResponse<CommandRun>> {
  return apiClient
    .post<ApiResponse<CommandRun>>(`/ai/commands/runs/${id}/approve`, undefined, {
      silent: true
    })
    .then((r) => r.data)
}

/**
 * 调用 POST /api/ai/commands/runs/{id}/reject 拒绝待审批运行记录。
 *
 * @param id 运行记录 ID
 * @returns 拒绝后的最新记录状态
 */
export function rejectCommandRun(id: number): Promise<ApiResponse<CommandRun>> {
  return apiClient
    .post<ApiResponse<CommandRun>>(`/ai/commands/runs/${id}/reject`, undefined, {
      silent: true
    })
    .then((r) => r.data)
}

/**
 * 调用 GET /api/ai/commands/templates 获取只读白名单命令模板列表。
 *
 * 模板与 Go Agent 侧冻结的 L1 模板表互为镜像;params[].pattern
 * 可用于前端表单预校验(后端仍会严格拦截非法参数)。
 *
 * @returns 模板数组
 */
export function listCommandTemplates(): Promise<ApiResponse<CommandTemplate[]>> {
  return apiClient
    .get<ApiResponse<CommandTemplate[]>>('/ai/commands/templates')
    .then((r) => r.data)
}

/**
 * 调用 GET /api/ai/commands/auto-approval-policy 读取实例级自动审批策略。
 *
 * 策略行缺失时后端按禁用返回(fail-closed),enabled=false 即人工审批模式。
 *
 * @returns 策略快照(enabled / max_risk_level / updated_at)
 */
export function getAutoApprovalPolicy(): Promise<ApiResponse<AutoApprovalPolicy>> {
  return apiClient
    .get<ApiResponse<AutoApprovalPolicy>>('/ai/commands/auto-approval-policy', { silent: true })
    .then((r) => r.data)
}

/**
 * 调用 PUT /api/ai/commands/auto-approval-policy 更新自动审批策略。
 *
 * max_risk_level 仅允许 low / medium(high 不可作为阈值,后端 40002 拒绝)。
 *
 * @param req 策略请求(enabled + max_risk_level 均必填)
 * @returns 更新后的策略快照
 */
export function updateAutoApprovalPolicy(
  req: AutoApprovalPolicyRequest
): Promise<ApiResponse<AutoApprovalPolicy>> {
  return apiClient
    .put<ApiResponse<AutoApprovalPolicy>>('/ai/commands/auto-approval-policy', req, {
      silent: true
    })
    .then((r) => r.data)
}
