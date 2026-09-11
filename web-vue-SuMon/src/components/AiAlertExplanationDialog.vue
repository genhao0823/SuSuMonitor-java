<template>
  <el-dialog
    :model-value="modelValue"
    title="告警 AI 智能解释"
    width="620px"
    :close-on-click-modal="false"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
    @close="onClose"
  >
    <div
      v-loading="loading"
      class="ai-explanation__body"
    >
      <!-- 加载失败:服务端异常等非 404 错误,与"暂无解释"空态区分,支持重试 -->
      <el-result
        v-if="!loading && loadFailed"
        icon="error"
        title="解释加载失败"
        :sub-title="loadErrorMessage"
      >
        <template #extra>
          <el-button
            type="primary"
            plain
            @click="retryFetch"
          >
            重试
          </el-button>
        </template>
      </el-result>

      <!-- 空态:后端对"未生成/生成失败/功能未开启"统一返回 404 -->
      <el-empty
        v-else-if="!loading && notFound"
        description="该告警暂无 AI 解释"
        :image-size="80"
      >
        <span class="ai-explanation__empty-hint">
          解释在告警触发后由服务端异步生成;若长期缺失,可能是 AI 解释功能未开启。
        </span>
      </el-empty>

      <template v-else-if="explanation !== null">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          :title="explanation.summary"
        />

        <template v-if="hasItems(explanation.possible_causes)">
          <h4 class="ai-explanation__section-title">
            可能原因
          </h4>
          <ul class="ai-explanation__list">
            <li
              v-for="(item, index) in explanation.possible_causes"
              :key="`cause-${index}`"
            >
              {{ item }}
            </li>
          </ul>
        </template>

        <template v-if="hasItems(explanation.impact)">
          <h4 class="ai-explanation__section-title">
            潜在影响
          </h4>
          <ul class="ai-explanation__list">
            <li
              v-for="(item, index) in explanation.impact"
              :key="`impact-${index}`"
            >
              {{ item }}
            </li>
          </ul>
        </template>

        <template v-if="hasItems(explanation.suggestions)">
          <h4 class="ai-explanation__section-title">
            排查建议
          </h4>
          <ol class="ai-explanation__list ai-explanation__ordered">
            <li
              v-for="(item, index) in explanation.suggestions"
              :key="`sugg-${index}`"
            >
              {{ item }}
            </li>
          </ol>
        </template>

        <template v-if="hasItems(explanation.limitations)">
          <h4 class="ai-explanation__section-title">
            边界说明
          </h4>
          <ul class="ai-explanation__list ai-explanation__limitations">
            <li
              v-for="(item, index) in explanation.limitations"
              :key="`limit-${index}`"
            >
              {{ item }}
            </li>
          </ul>
        </template>

        <p class="ai-explanation__meta">
          {{ explanation.provider }} / {{ explanation.model }} · prompt
          {{ explanation.prompt_version }}
          <template v-if="explanation.usage">
            · tokens {{ explanation.usage.total_tokens }}
          </template>
          <template v-if="explanation.created_at">
            · 生成于 {{ formatTime(explanation.created_at) }}
          </template>
        </p>
      </template>
    </div>

    <template #footer>
      <el-button
        type="primary"
        class="ai-explanation__submit"
        @click="emit('update:modelValue', false)"
      >
        知道了
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { describeAiError, getAlertExplanation } from '@/api/ai'
import { ElMessage } from 'element-plus'
import type { AiAlertExplanation } from '@/types/api'

/**
 * 告警 AI 智能解释回看弹窗(F1)。
 *
 * 解释由后端在告警触发后异步生成并落库;本弹窗只读回看。
 * 404(未生成 / 生成失败 / 功能未开启)统一渲染为空态而非报错。
 */
interface Props {
  modelValue: boolean
  recordId: number | null
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const loading = ref(false)
const notFound = ref(false)
const loadFailed = ref(false)
const loadErrorMessage = ref('')
const explanation = ref<AiAlertExplanation | null>(null)

watch(
  () => props.modelValue,
  (visible) => {
    if (visible && props.recordId !== null) {
      void fetchExplanation(props.recordId)
    }
  },
  { immediate: true }
)

async function fetchExplanation(recordId: number): Promise<void> {
  loading.value = true
  notFound.value = false
  loadFailed.value = false
  loadErrorMessage.value = ''
  explanation.value = null
  try {
    const res = await getAlertExplanation(recordId)
    if (res.data === null) {
      notFound.value = true
    } else {
      explanation.value = res.data
    }
  } catch (error) {
    loadFailed.value = true
    loadErrorMessage.value = describeAiError(error, '解释加载失败')
    ElMessage.error(loadErrorMessage.value)
  } finally {
    loading.value = false
  }
}

function retryFetch(): void {
  if (props.recordId !== null) {
    void fetchExplanation(props.recordId)
  }
}

function hasItems(items: string[] | undefined): boolean {
  return Array.isArray(items) && items.length > 0
}

function onClose(): void {
  explanation.value = null
  notFound.value = false
  loadFailed.value = false
  loadErrorMessage.value = ''
}

function formatTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}
</script>

<style scoped>
.ai-explanation__body {
  min-height: 160px;
  max-height: 60vh;
  overflow-y: auto;
  padding: 4px;
}

.ai-explanation__empty-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.ai-explanation__section-title {
  margin: 16px 0 8px;
  font-size: 13px;
  font-weight: 700;
  color: var(--susu-accent-deep);
}

.ai-explanation__list {
  margin: 0;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
  line-height: 1.6;
}

.ai-explanation__ordered {
  list-style: decimal;
}

.ai-explanation__limitations {
  color: var(--el-text-color-secondary);
}

.ai-explanation__meta {
  margin: 16px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.ai-explanation__submit {
  background: linear-gradient(135deg, #ff5b8a 0%, #b7325c 100%) !important;
  border: none !important;
  box-shadow: 0 6px 14px rgba(255, 91, 138, 0.3) !important;
}

.ai-explanation__submit:hover {
  background: linear-gradient(135deg, #ff7aa3 0%, #c8426f 100%) !important;
  box-shadow: 0 10px 20px rgba(255, 91, 138, 0.45) !important;
}
</style>
