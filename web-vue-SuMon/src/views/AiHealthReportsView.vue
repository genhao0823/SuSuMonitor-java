<template>
  <div class="ai-health-reports-view">
    <PageHeader
      title="AI 健康报告"
      subtitle="每日定时生成的全集群健康日报:在线率、指标峰值、告警统计与值得关注的三件事;支持历史回看与手动生成"
    >
      <template #actions>
        <el-date-picker
          v-model="generateDate"
          type="date"
          placeholder="报告日期(留空则生成昨日)"
          value-format="YYYY-MM-DD"
          :disabled-date="disableFutureDate"
          class="ai-health-reports-view__date-picker"
        />
        <el-button
          type="primary"
          :loading="generating"
          @click="onGenerate"
        >
          手动生成
        </el-button>
      </template>
    </PageHeader>

    <el-card
      class="ai-health-reports-view__card liquid-glass-card"
      shadow="never"
    >
      <el-alert
        v-if="error"
        type="error"
        :closable="false"
        show-icon
        :title="error"
        class="ai-health-reports-view__alert"
      />

      <el-table
        v-loading="loading"
        :data="items"
        stripe
        empty-text="暂无报告;点击右上角「手动生成」可立即生成一份"
      >
        <el-table-column
          label="报告日期"
          width="120"
        >
          <template #default="{ row }">
            <span class="ai-health-reports-view__date">{{ row.report_date }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="状态"
          width="96"
        >
          <template #default="{ row }">
            <el-tag
              :type="statusTagType(row.status)"
              size="small"
              effect="dark"
            >
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="摘要"
          min-width="280"
        >
          <template #default="{ row }">
            <span class="ai-health-reports-view__summary">{{ summaryFirstLine(row as AiHealthReport) }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="Token"
          width="96"
        >
          <template #default="{ row }">
            {{ row.usage?.total_tokens ?? 0 }}
          </template>
        </el-table-column>
        <el-table-column
          label="耗时"
          width="104"
        >
          <template #default="{ row }">
            {{ formatDuration(row.duration_ms) }}
          </template>
        </el-table-column>
        <el-table-column
          label="生成时间"
          width="176"
        >
          <template #default="{ row }">
            {{ formatDateTime(row.created_at) }}
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="88"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              @click="openDetail(row as AiHealthReport)"
            >
              详情
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <ServerPagination
        :page="page"
        :page-size="pageSize"
        :total="total"
        :page-size-options="pageSizeOptions"
        @update:page="onPageChange"
        @update:page-size="onPageSizeChange"
      />
    </el-card>

    <el-dialog
      v-model="detailVisible"
      :title="detailTitle"
      width="720px"
      top="6vh"
      class="ai-health-reports-view__dialog"
    >
      <div class="ai-health-reports-view__detail">
        <template v-if="detail">
          <el-alert
            v-if="detail.status === 'degraded'"
            type="warning"
            :closable="false"
            show-icon
            :title="degradedTitle(detail)"
            class="ai-health-reports-view__alert"
          />

          <section
            v-if="detail.summary"
            class="ai-health-reports-view__section"
          >
            <h3 class="ai-health-reports-view__section-title">
              摘要
            </h3>
            <p class="ai-health-reports-view__summary-text">
              {{ detail.summary }}
            </p>
          </section>

          <section
            v-if="detail.top_concerns.length"
            class="ai-health-reports-view__section"
          >
            <h3 class="ai-health-reports-view__section-title">
              值得关注
            </h3>
            <ol class="ai-health-reports-view__list">
              <li
                v-for="(concern, index) in detail.top_concerns"
                :key="index"
              >
                {{ concern }}
              </li>
            </ol>
          </section>

          <section class="ai-health-reports-view__section">
            <h3 class="ai-health-reports-view__section-title">
              服务器清单
            </h3>
            <p class="ai-health-reports-view__facts-line">
              共 {{ facts?.server_inventory.total_count ?? 0 }} 台,在线
              {{ facts?.server_inventory.online_count ?? 0 }} 台,离线
              {{ facts?.server_inventory.offline_count ?? 0 }} 台,在线率
              {{ facts?.server_inventory.online_rate ?? 0 }}%
            </p>
          </section>

          <section class="ai-health-reports-view__section">
            <h3 class="ai-health-reports-view__section-title">
              指标概览(窗口均值 / 峰值)
            </h3>
            <p class="ai-health-reports-view__facts-line">
              CPU {{ formatPercent(facts?.metric_peaks.avg_cpu_percent) }} /
              {{ formatPercent(facts?.metric_peaks.max_cpu_percent) }}(峰值服务器
              #{{ facts?.metric_peaks.max_cpu_server_id ?? '-' }});内存
              {{ formatPercent(facts?.metric_peaks.avg_memory_percent) }} /
              {{ formatPercent(facts?.metric_peaks.max_memory_percent) }};磁盘
              {{ formatPercent(facts?.metric_peaks.avg_disk_percent) }} /
              {{ formatPercent(facts?.metric_peaks.max_disk_percent) }}
            </p>
          </section>

          <section class="ai-health-reports-view__section">
            <h3 class="ai-health-reports-view__section-title">
              告警统计
            </h3>
            <p class="ai-health-reports-view__facts-line">
              触发 {{ facts?.alert_statistics.total_triggered ?? 0 }} 条
              (critical {{ facts?.alert_statistics.critical_count ?? 0 }} / warning
              {{ facts?.alert_statistics.warning_count ?? 0 }}),已恢复
              {{ facts?.alert_statistics.resolved_count ?? 0 }} 条,未恢复
              {{ facts?.alert_statistics.unresolved_count ?? 0 }} 条
            </p>
            <ul
              v-if="facts && facts.alert_statistics.top_servers.length"
              class="ai-health-reports-view__list"
            >
              <li
                v-for="server in facts.alert_statistics.top_servers"
                :key="server.server_id"
              >
                {{ server.server_name }}:#{{ server.server_id }} 共 {{ server.alert_count }} 条
                (critical {{ server.critical_count }})
              </li>
            </ul>
          </section>

          <section
            v-if="facts && facts.offline_servers.length"
            class="ai-health-reports-view__section"
          >
            <h3 class="ai-health-reports-view__section-title">
              离线服务器(生成时刻快照)
            </h3>
            <ul class="ai-health-reports-view__list">
              <li
                v-for="server in facts.offline_servers"
                :key="server.server_id"
              >
                {{ server.server_name }}:#{{ server.server_id }},Agent
                {{ server.agent_status }},最后心跳
                {{ server.last_heartbeat_at ?? '未知' }}
              </li>
            </ul>
          </section>

          <section
            v-if="detail.limitations.length"
            class="ai-health-reports-view__section"
          >
            <h3 class="ai-health-reports-view__section-title">
              局限说明
            </h3>
            <ul class="ai-health-reports-view__list ai-health-reports-view__list--muted">
              <li
                v-for="(limitation, index) in detail.limitations"
                :key="index"
              >
                {{ limitation }}
              </li>
            </ul>
          </section>

          <p class="ai-health-reports-view__meta">
            provider {{ detail.provider }} / model {{ detail.model || '-' }} / prompt
            {{ detail.prompt_version }} / token {{ detail.usage?.total_tokens ?? 0 }} / 耗时
            {{ formatDuration(detail.duration_ms) }}
          </p>
        </template>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">
          关闭
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import ServerPagination from '@/components/ServerPagination.vue'
import { describeAiError } from '@/api/ai'
import { generateHealthReport, listHealthReports } from '@/api/healthReport'
import { formatDateTime } from '@/utils/format'
import type { AiHealthReport, AiHealthReportStatus } from '@/types/api'

/**
 * AI 定时健康报告页(F3)。
 *
 * 数据契约:openapi-ai.json 0.6.0(snake_case);后端仅 ROLE_ADMIN 且
 * susumonitor.ai.report.enabled=true 时挂载,40400 视为"功能未开启"。
 * 列表行本身携带完整 facts,详情弹窗直接复用行数据,不再二次请求。
 */

const DEFAULT_PAGE_SIZE = 20
const pageSizeOptions = [10, 20, 50]

const loading = ref(false)
const error = ref<string | null>(null)
const items = ref<AiHealthReport[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(DEFAULT_PAGE_SIZE)

const detailVisible = ref(false)
const detail = ref<AiHealthReport | null>(null)
const generating = ref(false)
/** 手动生成目标日期(YYYY-MM-DD);留空提交 undefined 由后端取昨日。 */
const generateDate = ref<string>('')

const facts = computed(() => detail.value?.facts ?? null)
const detailTitle = computed(() =>
  detail.value ? `健康报告 ${detail.value.report_date}` : '健康报告'
)

onMounted(() => {
  void fetchReports()
})

async function fetchReports(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const res = await listHealthReports({ page: page.value, page_size: pageSize.value })
    items.value = res.data.items
    total.value = res.data.total
  } catch (err) {
    error.value = describeAiError(err, '健康报告加载失败,请稍后重试')
  } finally {
    loading.value = false
  }
}

function onPageChange(value: number): void {
  page.value = value
  void fetchReports()
}

function onPageSizeChange(value: number): void {
  pageSize.value = value
  page.value = 1
  void fetchReports()
}

function openDetail(row: AiHealthReport): void {
  detail.value = row
  detailVisible.value = true
}

/**
 * 手动生成报告:覆盖语义二次确认 → 生成 → 刷新列表并打开详情。
 * 生成耗时较高(LLM 单次调用),按钮 loading 期间禁止重复提交。
 */
async function onGenerate(): Promise<void> {
  const existing = generateDate.value
    ? items.value.some((item) => item.report_date === generateDate.value)
    : false
  try {
    await ElMessageBox.confirm(
      existing
        ? `该日期(${generateDate.value})已有报告,重新生成将覆盖旧报告。是否继续?`
        : '将按选定日期(留空则为昨日)聚合监控数据并调用模型生成报告,可能需要数十秒。是否继续?',
      '手动生成报告',
      { confirmButtonText: '生成', cancelButtonText: '取消', type: 'info' }
    )
  } catch {
    return
  }
  generating.value = true
  try {
    const res = await generateHealthReport(
      generateDate.value ? { report_date: generateDate.value } : {}
    )
    ElMessage.success(`报告 ${res.data.report_date} 已生成`)
    page.value = 1
    await fetchReports()
    detail.value = res.data
    detailVisible.value = true
  } catch (err) {
    ElMessage.error(describeAiError(err, '报告生成失败,请稍后重试'))
  } finally {
    generating.value = false
  }
}

function statusTagType(status: AiHealthReportStatus): 'success' | 'warning' {
  return status === 'succeeded' ? 'success' : 'warning'
}

function statusLabel(status: AiHealthReportStatus): string {
  return status === 'succeeded' ? '正常' : '降级'
}

function summaryFirstLine(row: AiHealthReport): string {
  if (row.summary) {
    const firstLine = row.summary.split('\n').find((line) => line.trim().length > 0)
    return firstLine ?? row.summary
  }
  return row.status === 'degraded' ? '模型摘要缺席,仅含聚合事实' : '-'
}

function degradedTitle(row: AiHealthReport): string {
  return `本报告为降级报告(错误码 ${row.error_code ?? '-'}):模型摘要不可用,以下仅含服务端聚合事实`
}

function formatDuration(durationMs: number | null | undefined): string {
  return durationMs == null ? '-' : `${durationMs} ms`
}

function formatPercent(value: number | null | undefined): string {
  return value == null ? '-' : `${value}%`
}

/** el-date-picker 禁选未来日期(后端对晚于当日的 report_date 返回 40002)。 */
function disableFutureDate(date: Date): boolean {
  const today = new Date()
  today.setHours(23, 59, 59, 999)
  return date.getTime() > today.getTime()
}
</script>

<style scoped>
.ai-health-reports-view__card {
  border-radius: 14px;
}

.ai-health-reports-view__alert {
  margin-bottom: 12px;
}

.ai-health-reports-view__date-picker {
  width: 220px;
}

.ai-health-reports-view__date {
  font-weight: 600;
}

.ai-health-reports-view__summary {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}

.ai-health-reports-view__section {
  margin-bottom: 16px;
}

.ai-health-reports-view__section-title {
  margin: 0 0 6px;
  font-size: 14px;
  font-weight: 700;
  color: var(--susu-text-dark, #27272a);
}

.ai-health-reports-view__summary-text {
  margin: 0;
  white-space: pre-wrap;
  line-height: 1.7;
  font-size: 13.5px;
}

.ai-health-reports-view__facts-line {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--susu-text-muted, #52525b);
}

.ai-health-reports-view__list {
  margin: 6px 0 0;
  padding-left: 20px;
  font-size: 13px;
  line-height: 1.8;
}

.ai-health-reports-view__list--muted {
  color: var(--susu-text-muted, #71717a);
}

.ai-health-reports-view__meta {
  margin: 16px 0 0;
  padding-top: 10px;
  border-top: 1px dashed rgba(39, 39, 42, 0.14);
  font-size: 12px;
  color: var(--susu-text-muted, #71717a);
}
</style>
