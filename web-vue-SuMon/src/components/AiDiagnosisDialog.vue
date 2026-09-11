<template>
  <el-dialog
    :model-value="modelValue"
    :title="`AI 智能诊断 · ${serverName}`"
    width="680px"
    :close-on-click-modal="false"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
    @close="onClose"
  >
    <!--
      提问区:仅在后端成功返回前展示;结果渲染后收起为"重新诊断"按钮,
      避免长结果与表单挤在同一个滚动视口里。
    -->
    <el-form
      v-if="diagnosis === null"
      class="ai-diagnosis__form"
      @submit.prevent="handleDiagnose"
    >
      <el-form-item label="关注历史窗口(分钟)">
        <el-input-number
          v-model="historyMinutes"
          :min="0"
          :max="1440"
          :step="30"
          controls-position="right"
        />
        <span class="ai-diagnosis__hint">最多回看 24 小时(1440 分钟),0 表示仅当前状态</span>
      </el-form-item>
      <el-form-item label="诊断问题">
        <el-input
          v-model="question"
          type="textarea"
          :rows="4"
          maxlength="4000"
          show-word-limit
          placeholder="例如:最近半小时 CPU 持续告警,可能是什么原因?"
        />
      </el-form-item>
    </el-form>

    <!-- 结果区:severity / 摘要 / 发现 / 证据 / 建议 / 限制 / 元信息 -->
    <div
      v-else
      class="ai-diagnosis__result"
    >
      <el-alert
        v-if="!diagnosis.model_used"
        type="warning"
        :closable="false"
        show-icon
        title="本次结果由服务端确定性摘要生成(未调用大模型)"
        description="AI 服务可能未开启或暂时不可用,以下为白名单监控事实汇总,仅供参考。"
      />
      <div class="ai-diagnosis__summary">
        <el-tag
          :type="severityTagType(diagnosis.severity)"
          effect="dark"
          size="small"
        >
          {{ severityLabel(diagnosis.severity) }}
        </el-tag>
        <span class="ai-diagnosis__summary-text">{{ diagnosis.summary }}</span>
      </div>

      <template v-if="diagnosis.findings.length > 0">
        <h4 class="ai-diagnosis__section-title">
          诊断发现
        </h4>
        <ul class="ai-diagnosis__list">
          <li
            v-for="(finding, index) in diagnosis.findings"
            :key="index"
            class="ai-diagnosis__finding"
          >
            <div class="ai-diagnosis__finding-head">
              <span class="ai-diagnosis__finding-title">{{ finding.title }}</span>
              <el-tag
                size="small"
                :type="confidenceTagType(finding.confidence)"
                effect="plain"
              >
                置信度 {{ confidenceLabel(finding.confidence) }}
              </el-tag>
            </div>
            <p class="ai-diagnosis__finding-desc">
              {{ finding.description }}
            </p>
          </li>
        </ul>
      </template>

      <template v-if="diagnosis.evidence.length > 0">
        <h4 class="ai-diagnosis__section-title">
          监控证据
        </h4>
        <el-table
          :data="diagnosis.evidence"
          size="small"
          class="ai-diagnosis__evidence"
        >
          <el-table-column
            prop="metric"
            label="指标"
            min-width="120"
          />
          <el-table-column label="采样值">
            <template #default="{ row }">
              {{ row.value ?? '—' }}
            </template>
          </el-table-column>
          <el-table-column
            prop="observed_at"
            label="观测时间"
            min-width="160"
          >
            <template #default="{ row }">
              {{ formatTime(row.observed_at) }}
            </template>
          </el-table-column>
          <el-table-column label="来源">
            <template #default="{ row }">
              {{ row.source === 'alert_summary' ? '告警摘要' : '监控摘要' }}
            </template>
          </el-table-column>
        </el-table>
      </template>

      <template v-if="diagnosis.recommendations.length > 0">
        <h4 class="ai-diagnosis__section-title">
          处理建议
        </h4>
        <ol class="ai-diagnosis__list ai-diagnosis__ordered">
          <li
            v-for="(item, index) in diagnosis.recommendations"
            :key="index"
          >
            {{ item }}
          </li>
        </ol>
      </template>

      <template v-if="diagnosis.limitations.length > 0">
        <h4 class="ai-diagnosis__section-title">
          边界说明
        </h4>
        <ul class="ai-diagnosis__list ai-diagnosis__limitations">
          <li
            v-for="(item, index) in diagnosis.limitations"
            :key="index"
          >
            {{ item }}
          </li>
        </ul>
      </template>

      <p class="ai-diagnosis__meta">
        {{ diagnosis.provider }} / {{ diagnosis.model }} · prompt {{ diagnosis.prompt_version }}
        <template v-if="diagnosis.usage">
          · tokens {{ diagnosis.usage.total_tokens }}(输入 {{ diagnosis.usage.input_tokens }} /
          输出 {{ diagnosis.usage.output_tokens }})
        </template>
      </p>
    </div>

    <template #footer>
      <template v-if="diagnosis === null">
        <el-button @click="emit('update:modelValue', false)">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="submitting"
          class="ai-diagnosis__submit"
          @click="handleDiagnose"
        >
          {{ submitting ? '诊断中…' : '开始诊断' }}
        </el-button>
      </template>
      <template v-else>
        <el-button @click="resetResult">
          重新诊断
        </el-button>
        <el-button
          type="primary"
          class="ai-diagnosis__submit"
          @click="emit('update:modelValue', false)"
        >
          完成
        </el-button>
      </template>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { describeAiError, requestAiDiagnosis } from '@/api/ai'
import type { AiConfidence, AiDiagnosis, AiSeverity } from '@/types/api'

/**
 * AI 只读诊断弹窗。
 *
 * 仅在服务器详情页由管理员唤起;serverId 由页面上下文注入,
 * 前端不提供任何自由命令或工具选择,诊断结果为建议性内容。
 */
interface Props {
  modelValue: boolean
  serverId: number | null
  serverName: string
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const question = ref('')
const historyMinutes = ref(30)
const submitting = ref(false)
const diagnosis = ref<AiDiagnosis | null>(null)

async function handleDiagnose(): Promise<void> {
  if (props.serverId === null) return
  if (question.value.trim().length === 0) {
    ElMessage.warning('请输入诊断问题')
    return
  }
  submitting.value = true
  try {
    const res = await requestAiDiagnosis({
      server_id: props.serverId,
      question: question.value.trim(),
      history_minutes: historyMinutes.value
    })
    diagnosis.value = res.data
  } catch (error) {
    ElMessage.error(describeAiError(error))
  } finally {
    submitting.value = false
  }
}

function resetResult(): void {
  diagnosis.value = null
}

function onClose(): void {
  diagnosis.value = null
  submitting.value = false
}

function severityLabel(severity: AiSeverity): string {
  switch (severity) {
    case 'critical':
      return '严重'
    case 'warning':
      return '警告'
    case 'info':
      return '正常'
    default:
      return '未知'
  }
}

function severityTagType(severity: AiSeverity): 'danger' | 'warning' | 'info' {
  switch (severity) {
    case 'critical':
      return 'danger'
    case 'warning':
      return 'warning'
    default:
      return 'info'
  }
}

function confidenceLabel(confidence: AiConfidence): string {
  switch (confidence) {
    case 'high':
      return '高'
    case 'medium':
      return '中'
    case 'low':
      return '低'
    default:
      return '未知'
  }
}

function confidenceTagType(confidence: AiConfidence): 'success' | 'warning' | 'info' {
  switch (confidence) {
    case 'high':
      return 'success'
    case 'medium':
      return 'warning'
    default:
      return 'info'
  }
}

function formatTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}
</script>

<style scoped>
.ai-diagnosis__form {
  padding: 8px 4px 0;
}

.ai-diagnosis__hint {
  margin-left: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.ai-diagnosis__result {
  max-height: 60vh;
  overflow-y: auto;
  padding: 4px;
}

.ai-diagnosis__summary {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-top: 12px;
}

.ai-diagnosis__summary-text {
  font-size: 14px;
  line-height: 1.6;
  color: var(--susu-ink);
}

.ai-diagnosis__section-title {
  margin: 16px 0 8px;
  font-size: 13px;
  font-weight: 700;
  color: var(--susu-accent-deep);
}

.ai-diagnosis__list {
  margin: 0;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 13px;
  line-height: 1.6;
}

.ai-diagnosis__ordered {
  list-style: decimal;
}

.ai-diagnosis__limitations {
  color: var(--el-text-color-secondary);
}

.ai-diagnosis__finding-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ai-diagnosis__finding-title {
  font-weight: 600;
}

.ai-diagnosis__finding-desc {
  margin: 4px 0 0;
  color: var(--el-text-color-regular);
}

.ai-diagnosis__evidence {
  width: 100%;
}

.ai-diagnosis__meta {
  margin: 16px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.ai-diagnosis__submit {
  background: linear-gradient(135deg, #ff5b8a 0%, #b7325c 100%) !important;
  border: none !important;
  box-shadow: 0 6px 14px rgba(255, 91, 138, 0.3) !important;
}

.ai-diagnosis__submit:hover {
  background: linear-gradient(135deg, #ff7aa3 0%, #c8426f 100%) !important;
  box-shadow: 0 10px 20px rgba(255, 91, 138, 0.45) !important;
}
</style>
