<template>
  <div class="ai-commands-view">
    <PageHeader
      title="AI 命令域"
      subtitle="审批制只读命令:AI 或人工基于白名单模板提案,管理员审批后经 Agent 受限执行;不存在自由命令"
    />

    <el-card
      class="ai-commands-view__card liquid-glass-card"
      shadow="never"
    >
      <el-tabs v-model="activeTab">
        <!-- ==================== 运行记录 ==================== -->
        <el-tab-pane
          label="运行记录"
          name="runs"
        >
          <div class="ai-commands-view__filters">
            <el-select
              v-model="runServerFilter"
              placeholder="服务器"
              clearable
              class="ai-commands-view__filter"
              @change="onFilterChange"
            >
              <el-option
                v-for="item in serverOptions"
                :key="item.id"
                :label="`${item.name} (#${item.id})`"
                :value="item.id"
              />
            </el-select>
            <el-select
              v-model="runStatusFilter"
              placeholder="状态"
              clearable
              class="ai-commands-view__filter"
              @change="onFilterChange"
            >
              <el-option
                v-for="item in STATUS_OPTIONS"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
            <el-button
              :loading="runsLoading"
              @click="reloadRuns"
            >
              刷新
            </el-button>
          </div>

          <el-table
            v-loading="runsLoading"
            :data="runs"
            stripe
            empty-text="当前筛选条件下暂无命令运行记录"
          >
            <el-table-column
              prop="id"
              label="ID"
              width="70"
            />
            <el-table-column
              prop="server_id"
              label="服务器"
              width="90"
            >
              <template #default="{ row }">
                #{{ row.server_id }}
              </template>
            </el-table-column>
            <el-table-column
              prop="template_id"
              label="模板"
              min-width="150"
              show-overflow-tooltip
            />
            <el-table-column
              prop="rendered_command"
              label="执行命令"
              min-width="240"
              show-overflow-tooltip
            >
              <template #default="{ row }">
                <code class="ai-commands-view__cmd">{{ row.rendered_command }}</code>
              </template>
            </el-table-column>
            <el-table-column
              prop="source"
              label="来源"
              width="120"
            >
              <template #default="{ row }">
                <el-tag
                  size="small"
                  :type="row.source === 'ai' ? 'warning' : 'info'"
                  effect="plain"
                >
                  {{ row.source === 'ai' ? 'AI 建议' : '手动' }}
                </el-tag>
                <el-tag
                  v-if="row.approval_mode === 'auto'"
                  size="small"
                  type="success"
                  effect="plain"
                  class="ai-commands-view__auto-badge"
                >
                  自动
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column
              prop="risk_level"
              label="风险"
              width="80"
            >
              <template #default="{ row }">
                <el-tag
                  size="small"
                  :type="riskTagType(row.risk_level)"
                  effect="plain"
                >
                  {{ riskLabel(row.risk_level) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column
              prop="status"
              label="状态"
              width="110"
            >
              <template #default="{ row }">
                <el-tag
                  size="small"
                  :type="statusTagType(row.status)"
                >
                  {{ statusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column
              prop="created_at"
              label="创建时间"
              min-width="160"
            >
              <template #default="{ row }">
                {{ formatDateTime(row.created_at) }}
              </template>
            </el-table-column>
            <el-table-column
              label="操作"
              width="220"
              fixed="right"
            >
              <template #default="{ row }">
                <div class="table-action-group">
                  <el-button
                    size="small"
                    plain
                    @click="openDetail(row as CommandRun)"
                  >
                    详情
                  </el-button>
                  <el-button
                    v-if="row.status === 'pending_approval'"
                    size="small"
                    type="success"
                    plain
                    :loading="actingId === row.id"
                    @click="handleApprove(row as CommandRun)"
                  >
                    通过
                  </el-button>
                  <el-button
                    v-if="row.status === 'pending_approval'"
                    size="small"
                    type="danger"
                    plain
                    :loading="actingId === row.id"
                    @click="handleReject(row as CommandRun)"
                  >
                    拒绝
                  </el-button>
                </div>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            v-model:current-page="runPage"
            v-model:page-size="runPageSize"
            :total="runTotal"
            :page-sizes="pageSizeOptions"
            layout="total, sizes, prev, pager, next"
            class="ai-commands-view__pagination"
            @current-change="onPageChange"
            @size-change="onPageSizeChange"
          />
        </el-tab-pane>

        <!-- ==================== 创建命令 ==================== -->
        <el-tab-pane
          label="创建命令"
          name="create"
        >
          <el-radio-group
            v-model="createMode"
            class="ai-commands-view__mode"
          >
            <el-radio-button value="ai">
              AI 建议
            </el-radio-button>
            <el-radio-button value="manual">
              手动创建
            </el-radio-button>
          </el-radio-group>

          <!-- AI 建议:描述运维意图,由模型选择白名单模板 -->
          <el-form
            v-if="createMode === 'ai'"
            class="ai-commands-view__form"
            @submit.prevent="handleSuggest"
          >
            <el-form-item
              label="目标服务器"
              required
            >
              <el-select
                v-model="suggestServerId"
                placeholder="选择服务器"
                class="ai-commands-view__server-select"
              >
                <el-option
                  v-for="item in serverOptions"
                  :key="item.id"
                  :label="`${item.name} (#${item.id})`"
                  :value="item.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item
              label="运维意图"
              required
            >
              <el-input
                v-model="suggestIntent"
                type="textarea"
                :rows="3"
                maxlength="2000"
                show-word-limit
                placeholder="例如:查看占用内存最多的前 5 个进程 / 检查 nginx 服务状态"
              />
            </el-form-item>
            <el-button
              type="primary"
              class="ai-commands-view__submit"
              :loading="suggesting"
              :disabled="suggestServerId === null || suggestIntent.trim().length === 0"
              @click="handleSuggest"
            >
              生成建议
            </el-button>

            <template v-if="suggestedRuns.length > 0">
              <el-alert
                type="success"
                :closable="false"
                show-icon
                class="ai-commands-view__suggest-result"
                :title="suggestResultTitle"
              />
              <el-table
                :data="suggestedRuns"
                size="small"
                stripe
              >
                <el-table-column
                  prop="id"
                  label="ID"
                  width="70"
                />
                <el-table-column
                  prop="rendered_command"
                  label="命令"
                  min-width="220"
                >
                  <template #default="{ row }">
                    <code class="ai-commands-view__cmd">{{ row.rendered_command }}</code>
                  </template>
                </el-table-column>
                <el-table-column
                  prop="status"
                  label="状态"
                  width="110"
                >
                  <template #default="{ row }">
                    <el-tag
                      size="small"
                      :type="statusTagType(row.status)"
                    >
                      {{ statusLabel(row.status) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column
                  prop="proposal.reason"
                  label="AI 理由"
                  min-width="200"
                  show-overflow-tooltip
                />
              </el-table>
            </template>
          </el-form>

          <!-- 手动创建:选模板 + 按模板参数正则校验 -->
          <el-form
            v-else
            ref="manualFormRef"
            class="ai-commands-view__form"
            @submit.prevent="handleManualCreate"
          >
            <el-form-item
              label="目标服务器"
              required
            >
              <el-select
                v-model="manualServerId"
                placeholder="选择服务器"
                class="ai-commands-view__server-select"
              >
                <el-option
                  v-for="item in serverOptions"
                  :key="item.id"
                  :label="`${item.name} (#${item.id})`"
                  :value="item.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item
              label="命令模板"
              required
            >
              <el-select
                v-model="manualTemplateId"
                placeholder="选择白名单模板"
                class="ai-commands-view__server-select"
                @change="onTemplateChange"
              >
                <el-option
                  v-for="tpl in templates"
                  :key="tpl.id"
                  :label="tpl.id"
                  :value="tpl.id"
                />
              </el-select>
              <div
                v-if="selectedTemplate"
                class="ai-commands-view__argv"
              >
                固定命令:
                <code>{{ selectedTemplate.argv.join(' ') }}</code>
              </div>
            </el-form-item>
            <el-form-item
              v-for="param in selectedTemplate?.params ?? []"
              :key="param.name"
              :label="param.name"
              :prop="param.name"
              :rules="paramRule(param)"
            >
              <el-input
                v-model="manualParams[param.name]"
                :placeholder="`需满足正则 ${param.pattern}`"
              />
            </el-form-item>

            <el-button
              type="primary"
              class="ai-commands-view__submit"
              :loading="creating"
              :disabled="manualServerId === null || manualTemplateId === null"
              @click="handleManualCreate"
            >
              创建待审批命令
            </el-button>
          </el-form>
        </el-tab-pane>

        <!-- ==================== 自动审批 ==================== -->
        <el-tab-pane
          label="自动审批"
          name="policy"
        >
          <div
            v-loading="policyLoading"
            class="ai-commands-view__policy"
          >
            <el-alert
              v-if="policyForm.enabled"
              type="success"
              :closable="false"
              show-icon
              class="ai-commands-view__policy-alert"
              :title="`自动审批已开启:AI 建议的${policyThresholdLabel}命令将跳过人工审批直接下发执行`"
              description="仍仅限白名单只读命令;模板与参数校验、执行通道防线不变;可随时关闭恢复人工审批。"
            />
            <el-alert
              v-else
              type="info"
              :closable="false"
              show-icon
              class="ai-commands-view__policy-alert"
              title="自动审批未开启:所有命令(含 AI 建议)均需人工审批后才会执行"
            />

            <div class="ai-commands-view__policy-row">
              <span class="ai-commands-view__policy-label">启用自动审批</span>
              <el-switch v-model="policyForm.enabled" />
            </div>
            <div class="ai-commands-view__policy-row">
              <span class="ai-commands-view__policy-label">风险阈值</span>
              <el-radio-group
                v-model="policyForm.max_risk_level"
                :disabled="!policyForm.enabled"
              >
                <el-radio-button value="low">
                  仅低风险
                </el-radio-button>
                <el-radio-button value="medium">
                  中低风险
                </el-radio-button>
              </el-radio-group>
            </div>
            <p
              v-if="policyUpdatedAt"
              class="ai-commands-view__policy-meta"
            >
              上次修改:{{ formatDateTime(policyUpdatedAt) }}
            </p>
            <el-button
              type="primary"
              class="ai-commands-view__submit"
              :loading="policySaving"
              @click="handleSavePolicy"
            >
              保存策略
            </el-button>
            <p class="ai-commands-view__policy-hint">
              说明:只影响 AI 建议来源的命令;手动创建的命令始终需要人工审批。
              高风险命令永不自动执行。实例级总开关由服务端配置(AI_COMMAND_ENABLED)控制。
            </p>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 运行详情弹窗:审批通过后自动轮询执行进度 -->
    <el-dialog
      v-model="detailVisible"
      :title="`命令运行详情 · #${detailRun?.id ?? ''}`"
      width="680px"
      :close-on-click-modal="false"
      @close="stopPolling"
    >
      <template v-if="detailRun">
        <el-descriptions
          :column="2"
          size="small"
          border
        >
          <el-descriptions-item label="状态">
            <el-tag
              size="small"
              :type="statusTagType(detailRun.status)"
            >
              {{ statusLabel(detailRun.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="服务器">
            #{{ detailRun.server_id }}
          </el-descriptions-item>
          <el-descriptions-item label="模板">
            {{ detailRun.template_id }}
          </el-descriptions-item>
          <el-descriptions-item label="风险等级">
            <el-tag
              size="small"
              :type="riskTagType(detailRun.risk_level)"
              effect="plain"
            >
              {{ riskLabel(detailRun.risk_level) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="审批方式">
            <el-tag
              size="small"
              :type="detailRun.approval_mode === 'auto' ? 'success' : 'info'"
              effect="plain"
            >
              {{ detailRun.approval_mode === 'auto' ? '策略自动' : '人工审批' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="退出码">
            {{ detailRun.exit_code ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item
            label="创建时间"
            :span="2"
          >
            {{ formatDateTime(detailRun.created_at) }}
          </el-descriptions-item>
          <el-descriptions-item
            v-if="detailRun.completed_at"
            label="完成时间"
            :span="2"
          >
            {{ formatDateTime(detailRun.completed_at) }}
          </el-descriptions-item>
        </el-descriptions>

        <h4 class="ai-commands-view__section-title">
          执行命令(审批即视为确认该内容)
        </h4>
        <pre class="ai-commands-view__pre">{{ detailRun.rendered_command }}</pre>

        <template v-if="detailRun.proposal">
          <h4 class="ai-commands-view__section-title">
            AI 提案理由({{ detailRun.proposal.model }})
          </h4>
          <p class="ai-commands-view__reason">
            {{ detailRun.proposal.reason }}
          </p>
        </template>

        <template v-if="detailRun.result">
          <h4 class="ai-commands-view__section-title">
            执行输出
            <el-tag
              v-if="detailRun.result.truncated"
              size="small"
              type="warning"
              effect="plain"
            >
              已截断
            </el-tag>
          </h4>
          <pre class="ai-commands-view__pre">{{ detailRun.result.stdout || '(无标准输出)' }}</pre>
          <template v-if="detailRun.result.stderr">
            <h4 class="ai-commands-view__section-title">
              标准错误
            </h4>
            <pre class="ai-commands-view__pre ai-commands-view__pre--err">{{ detailRun.result.stderr }}</pre>
          </template>
          <el-alert
            v-if="detailRun.result.error"
            type="error"
            :closable="false"
            show-icon
            :title="detailRun.result.error"
          />
        </template>
        <el-alert
          v-else-if="detailRun.status === 'approved' || detailRun.status === 'executing'"
          type="info"
          :closable="false"
          show-icon
          title="命令已下发,正在等待 Agent 回传执行结果…"
        />
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">
          关闭
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormItemRule } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import {
  approveCommandRun,
  createManualCommandRun,
  getAutoApprovalPolicy,
  getCommandRun,
  listCommandRuns,
  listCommandTemplates,
  rejectCommandRun,
  requestCommandSuggestions,
  updateAutoApprovalPolicy
} from '@/api/command'
import { describeAiError } from '@/api/ai'
import { listServers } from '@/api/server'
import { formatDateTime } from '@/utils/format'
import type {
  AutoApprovalPolicy,
  CommandRiskLevel,
  CommandRun,
  CommandRunStatus,
  CommandTemplate,
  CommandTemplateParam,
  Server
} from '@/types/api'

/**
 * AI 命令域页面(M1 审批制 + 策略自动审批)。
 *
 * - 运行记录:分页查询 + 待审批记录的通过 / 拒绝;详情中对 approved/executing
 *   状态每 2 秒轮询一次直到终态(后端无执行进度推送)。
 * - 创建命令:AI 建议(意图 → 白名单模板提案)与手动创建(模板 + 参数正则校验),
 *   两者都先产生 pending_approval 记录;若自动审批策略开启且风险达标,
 *   服务端会立即自动审批并下发,前端对建议结果轮询跟进。
 * - 自动审批:实例级策略(开关 + 风险阈值),仅影响 AI 建议来源命令。
 */

/** 命令运行终态集合;轮询到达终态即停止。 */
const TERMINAL_STATUSES: CommandRunStatus[] = [
  'succeeded',
  'failed',
  'rejected',
  'expired',
  'timeout'
]

const STATUS_OPTIONS: Array<{ value: CommandRunStatus; label: string }> = [
  { value: 'pending_approval', label: '待审批' },
  { value: 'approved', label: '已通过' },
  { value: 'executing', label: '执行中' },
  { value: 'succeeded', label: '成功' },
  { value: 'failed', label: '失败' },
  { value: 'rejected', label: '已拒绝' },
  { value: 'expired', label: '已过期' },
  { value: 'timeout', label: '已超时' }
]

const activeTab = ref<'runs' | 'create' | 'policy'>('runs')

/** 服务器下拉选项(两个 Tab 共用)。 */
const serverOptions = ref<Server[]>([])
const pageSizeOptions: number[] = [10, 20, 50, 100]

/* ------------------------------ 运行记录 ------------------------------ */
const runs = ref<CommandRun[]>([])
const runsLoading = ref(false)
const runServerFilter = ref<number | null>(null)
const runStatusFilter = ref<CommandRunStatus | null>(null)
const runPage = ref(1)
const runPageSize = ref<number>(pageSizeOptions[0])
const runTotal = ref(0)
const actingId = ref<number | null>(null)

/* ------------------------------ 详情与轮询 ------------------------------ */
const detailVisible = ref(false)
const detailRun = ref<CommandRun | null>(null)
const POLL_INTERVAL_MS = 2_000
let pollTimer: ReturnType<typeof setInterval> | null = null

/* ------------------------------ 创建命令 ------------------------------ */
const createMode = ref<'ai' | 'manual'>('ai')

const suggesting = ref(false)
const suggestServerId = ref<number | null>(null)
const suggestIntent = ref('')
const suggestedRuns = ref<CommandRun[]>([])

const creating = ref(false)
const manualFormRef = ref<FormInstance>()
const manualServerId = ref<number | null>(null)
const manualTemplateId = ref<string | null>(null)
const manualParams = reactive<Record<string, string>>({})
const templates = ref<CommandTemplate[]>([])

/* ------------------------------ 自动审批策略 ------------------------------ */
const policyLoading = ref(false)
const policySaving = ref(false)
const policyForm = reactive<{ enabled: boolean; max_risk_level: 'low' | 'medium' }>({
  enabled: false,
  max_risk_level: 'medium'
})
const policyUpdatedAt = ref<string | null>(null)

/** 建议结果的独立轮询:自动审批后命令已在执行,跟进到终态。 */
let suggestPollTimer: ReturnType<typeof setInterval> | null = null

const selectedTemplate = computed<CommandTemplate | null>(
  () => templates.value.find((tpl) => tpl.id === manualTemplateId.value) ?? null
)

onMounted(async () => {
  await loadServerOptions()
  await reloadRuns()
  await loadTemplates()
  await loadPolicy()
})

onBeforeUnmount(() => {
  stopPolling()
  stopSuggestPolling()
})

async function loadServerOptions(): Promise<void> {
  try {
    const response = await listServers({ page: 1, page_size: 100, sort_by: 'id', sort_order: 'asc' })
    serverOptions.value = response.data?.items ?? []
  } catch {
    serverOptions.value = []
  }
}

/** 模板与列表并行加载;模板加载失败仅禁用手动创建的模板选择。 */
async function loadTemplates(): Promise<void> {
  try {
    const response = await listCommandTemplates()
    templates.value = response.data ?? []
  } catch (error) {
    templates.value = []
    ElMessage.error(describeAiError(error, '命令模板加载失败'))
  }
}

function buildRunQuery(): { server_id?: number; status?: CommandRunStatus; page: number; page_size: number } {
  const q: ReturnType<typeof buildRunQuery> = {
    page: runPage.value,
    page_size: runPageSize.value
  }
  if (runServerFilter.value !== null) q.server_id = runServerFilter.value
  if (runStatusFilter.value !== null) q.status = runStatusFilter.value
  return q
}

async function reloadRuns(): Promise<void> {
  runsLoading.value = true
  try {
    const response = await listCommandRuns(buildRunQuery())
    runs.value = response.data?.items ?? []
    runTotal.value = response.data?.total ?? 0
  } catch (error) {
    ElMessage.error(describeAiError(error, '运行记录加载失败'))
  } finally {
    runsLoading.value = false
  }
}

function onFilterChange(): void {
  runPage.value = 1
  void reloadRuns()
}

function onPageChange(next: number): void {
  runPage.value = next
  void reloadRuns()
}

function onPageSizeChange(next: number): void {
  runPageSize.value = next
  runPage.value = 1
  void reloadRuns()
}

/* ------------------------------ 审批操作 ------------------------------ */

async function handleApprove(run: CommandRun): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认批准并下发以下命令到服务器 #${run.server_id}?\n${run.rendered_command}`,
      '审批确认',
      { confirmButtonText: '批准并下发', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  actingId.value = run.id
  try {
    const res = await approveCommandRun(run.id)
    ElMessage.success('已批准,命令下发中')
    await reloadRuns()
    // 详情若打开则跟进最新状态并开始轮询执行进度。
    if (detailVisible.value && detailRun.value?.id === run.id) {
      detailRun.value = res.data
      startPolling(run.id)
    }
  } catch (error) {
    ElMessage.error(describeAiError(error))
    await reloadRuns()
  } finally {
    actingId.value = null
  }
}

async function handleReject(run: CommandRun): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认拒绝该命令提案?\n${run.rendered_command}`,
      '拒绝确认',
      { confirmButtonText: '拒绝', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  actingId.value = run.id
  try {
    await rejectCommandRun(run.id)
    ElMessage.success('已拒绝')
    await reloadRuns()
  } catch (error) {
    ElMessage.error(describeAiError(error))
    await reloadRuns()
  } finally {
    actingId.value = null
  }
}

/* ------------------------------ 详情与轮询 ------------------------------ */

function openDetail(run: CommandRun): void {
  detailRun.value = run
  detailVisible.value = true
  // 已批准 / 执行中的记录打开详情即开始轮询,直到终态或关闭弹窗。
  if (run.status === 'approved' || run.status === 'executing') {
    startPolling(run.id)
  } else {
    stopPolling()
  }
}

function startPolling(runId: number): void {
  stopPolling()
  pollTimer = setInterval(() => {
    void pollOnce(runId)
  }, POLL_INTERVAL_MS)
}

async function pollOnce(runId: number): Promise<void> {
  try {
    const res = await getCommandRun(runId)
    const next = res.data
    if (detailRun.value?.id === next.id) {
      detailRun.value = next
    }
    const row = runs.value.find((item) => item.id === next.id)
    if (row !== undefined) {
      Object.assign(row, next)
    }
    if (TERMINAL_STATUSES.includes(next.status)) {
      stopPolling()
    }
  } catch {
    // 单次轮询失败不打断;下一次间隔继续尝试。
  }
}

function stopPolling(): void {
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

/* ------------------------------ AI 建议 ------------------------------ */

/** 建议结果提示:按是否已有命令被自动审批下发切换文案。 */
const suggestResultTitle = computed(() => {
  const dispatched = suggestedRuns.value.some(
    (run) => run.approval_mode === 'auto' && run.status !== 'pending_approval'
  )
  return dispatched
    ? '已生成命令并按自动审批策略直接下发,状态见下表;可在「运行记录」查看详情'
    : '已生成待审批命令,请在「运行记录」中审批后才会执行'
})

async function handleSuggest(): Promise<void> {
  if (suggestServerId.value === null || suggesting.value) return
  suggesting.value = true
  suggestedRuns.value = []
  try {
    const res = await requestCommandSuggestions({
      server_id: suggestServerId.value,
      intent: suggestIntent.value.trim()
    })
    suggestedRuns.value = res.data ?? []
    if (suggestedRuns.value.length === 0) {
      ElMessage.warning('AI 未给出可用的白名单命令建议,请换一种意图描述')
    } else {
      ElMessage.success(`已生成 ${suggestedRuns.value.length} 条命令建议`)
      await reloadRuns()
      // 自动审批下发的命令已在执行,对建议列表轮询跟进到终态。
      startSuggestPolling()
    }
  } catch (error) {
    ElMessage.error(describeAiError(error))
  } finally {
    suggesting.value = false
  }
}

/** 仅在存在非终态建议时轮询;每 tick 拉取全部非终态行并就地更新。 */
function startSuggestPolling(): void {
  stopSuggestPolling()
  if (!suggestedRuns.value.some((run) => !TERMINAL_STATUSES.includes(run.status))) return
  suggestPollTimer = setInterval(() => {
    void pollSuggestedOnce()
  }, POLL_INTERVAL_MS)
}

async function pollSuggestedOnce(): Promise<void> {
  const active = suggestedRuns.value.filter((run) => !TERMINAL_STATUSES.includes(run.status))
  if (active.length === 0) {
    stopSuggestPolling()
    return
  }
  try {
    const results = await Promise.all(active.map((run) => getCommandRun(run.id)))
    for (const res of results) {
      const next = res.data
      if (next === undefined) continue
      const row = suggestedRuns.value.find((item) => item.id === next.id)
      if (row !== undefined) Object.assign(row, next)
      const listRow = runs.value.find((item) => item.id === next.id)
      if (listRow !== undefined) Object.assign(listRow, next)
    }
    if (!suggestedRuns.value.some((run) => !TERMINAL_STATUSES.includes(run.status))) {
      stopSuggestPolling()
    }
  } catch {
    // 单次轮询失败不打断;下一次间隔继续尝试。
  }
}

function stopSuggestPolling(): void {
  if (suggestPollTimer !== null) {
    clearInterval(suggestPollTimer)
    suggestPollTimer = null
  }
}

/* ------------------------------ 自动审批策略 ------------------------------ */

async function loadPolicy(): Promise<void> {
  policyLoading.value = true
  try {
    const res = await getAutoApprovalPolicy()
    const policy: AutoApprovalPolicy | undefined = res.data
    if (policy !== undefined) {
      policyForm.enabled = policy.enabled
      policyForm.max_risk_level = policy.max_risk_level === 'low' ? 'low' : 'medium'
      policyUpdatedAt.value = policy.updated_at ?? null
    }
  } catch {
    // 策略读取失败仅影响本 Tab 展示,按默认禁用呈现,不打扰其他 Tab。
    policyForm.enabled = false
  } finally {
    policyLoading.value = false
  }
}

async function handleSavePolicy(): Promise<void> {
  if (policySaving.value) return
  policySaving.value = true
  try {
    const res = await updateAutoApprovalPolicy({
      enabled: policyForm.enabled,
      max_risk_level: policyForm.max_risk_level
    })
    policyUpdatedAt.value = res.data?.updated_at ?? null
    ElMessage.success(
      policyForm.enabled ? '自动审批策略已开启' : '自动审批已关闭,恢复人工审批'
    )
  } catch (error) {
    ElMessage.error(describeAiError(error, '策略保存失败'))
  } finally {
    policySaving.value = false
  }
}

const policyThresholdLabel = computed(() =>
  policyForm.max_risk_level === 'low' ? '低风险' : '中低风险'
)

/* ------------------------------ 手动创建 ------------------------------ */

function onTemplateChange(): void {
  Object.keys(manualParams).forEach((key) => delete manualParams[key])
}

/** 依据模板冻结正则生成动态表单校验规则。 */
function paramRule(param: CommandTemplateParam): FormItemRule[] {
  let compiled: RegExp | null = null
  try {
    compiled = new RegExp(param.pattern)
  } catch {
    compiled = null
  }
  return [
    {
      validator: (_rule, value: string, cb: (error?: Error) => void) => {
        const text = typeof value === 'string' ? value : ''
        if (text.length === 0) {
          cb(new Error(`请输入 ${param.name}`))
          return
        }
        if (compiled !== null && !compiled.test(text)) {
          cb(new Error('参数不符合模板白名单正则'))
          return
        }
        cb()
      },
      trigger: 'blur'
    }
  ]
}

async function handleManualCreate(): Promise<void> {
  if (manualServerId.value === null || manualTemplateId.value === null || creating.value) return
  if (manualFormRef.value !== undefined) {
    try {
      await manualFormRef.value.validate()
    } catch {
      return
    }
  }
  // 只提交非空参数;空对象时不携带 params 字段(后端 ignoreUnknown=false)。
  const params: Record<string, string> = {}
  for (const [key, value] of Object.entries(manualParams)) {
    if (typeof value === 'string' && value.length > 0) {
      params[key] = value
    }
  }
  creating.value = true
  try {
    const res = await createManualCommandRun({
      server_id: manualServerId.value,
      template_id: manualTemplateId.value,
      ...(Object.keys(params).length > 0 ? { params } : {})
    })
    ElMessage.success(`已创建待审批命令 #${res.data.id}`)
    suggestedRuns.value = [res.data]
    activeTab.value = 'runs'
    await reloadRuns()
  } catch (error) {
    ElMessage.error(describeAiError(error))
  } finally {
    creating.value = false
  }
}

/* ------------------------------ 展示辅助 ------------------------------ */

function statusLabel(status: CommandRunStatus): string {
  return STATUS_OPTIONS.find((item) => item.value === status)?.label ?? status
}

function statusTagType(status: CommandRunStatus): 'success' | 'warning' | 'info' | 'danger' | 'primary' {
  switch (status) {
    case 'pending_approval':
      return 'warning'
    case 'approved':
    case 'executing':
      return 'primary'
    case 'succeeded':
      return 'success'
    case 'failed':
    case 'timeout':
      return 'danger'
    default:
      return 'info'
  }
}

function riskLabel(risk: CommandRiskLevel | undefined): string {
  switch (risk) {
    case 'medium':
      return '中'
    case 'high':
      return '高'
    case 'low':
    default:
      return '低'
  }
}

function riskTagType(risk: CommandRiskLevel | undefined): 'success' | 'warning' | 'danger' {
  switch (risk) {
    case 'medium':
      return 'warning'
    case 'high':
      return 'danger'
    case 'low':
    default:
      return 'success'
  }
}
</script>

<style scoped>
.ai-commands-view {
  max-width: 1280px;
  margin: 0 auto;
}

.ai-commands-view__card :deep(.el-card__body) {
  padding: 20px;
}

.ai-commands-view__filters {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.ai-commands-view__filter {
  width: 200px;
}

.ai-commands-view__pagination {
  margin-top: 16px;
  justify-content: flex-end;
}

.ai-commands-view__cmd {
  font-family: var(--el-font-family-monospace, monospace);
  font-size: 12px;
  background: rgba(168, 44, 81, 0.08);
  padding: 2px 6px;
  border-radius: 4px;
}

.ai-commands-view__mode {
  margin-bottom: 16px;
}

.ai-commands-view__form {
  max-width: 720px;
}

.ai-commands-view__server-select {
  width: 320px;
}

.ai-commands-view__argv {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.ai-commands-view__suggest-result {
  margin-top: 16px;
  margin-bottom: 8px;
}

.ai-commands-view__section-title {
  margin: 16px 0 8px;
  font-size: 13px;
  font-weight: 700;
  color: var(--susu-accent-deep);
}

.ai-commands-view__pre {
  margin: 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: rgba(168, 44, 81, 0.06);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 260px;
  overflow-y: auto;
}

.ai-commands-view__pre--err {
  background: rgba(245, 108, 108, 0.08);
  color: var(--el-color-danger);
}

.ai-commands-view__reason {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
}

.ai-commands-view__submit {
  background: linear-gradient(135deg, #ff5b8a 0%, #b7325c 100%) !important;
  border: none !important;
  box-shadow: 0 6px 14px rgba(255, 91, 138, 0.3) !important;
}

.ai-commands-view__submit:hover {
  background: linear-gradient(135deg, #ff7aa3 0%, #c8426f 100%) !important;
  box-shadow: 0 10px 20px rgba(255, 91, 138, 0.45) !important;
}

.ai-commands-view__auto-badge {
  margin-left: 4px;
}

.ai-commands-view__policy {
  max-width: 640px;
}

.ai-commands-view__policy-alert {
  margin-bottom: 16px;
}

.ai-commands-view__policy-row {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
}

.ai-commands-view__policy-label {
  min-width: 96px;
  font-size: 14px;
  color: var(--susu-ink);
}

.ai-commands-view__policy-meta {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.ai-commands-view__policy-hint {
  margin-top: 12px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}

@media (max-width: 640px) {
  .ai-commands-view__pagination {
    justify-content: flex-start;
    flex-wrap: wrap;
    row-gap: 8px;
  }
}
</style>
