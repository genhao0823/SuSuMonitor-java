<template>
  <div class="ai-qa-view">
    <PageHeader
      title="AI 运维问答"
      subtitle="基于只读工具的智能问答；模型只能查询服务器状态、指标、告警与命令模板，不能执行任何操作"
    >
      <template #actions>
        <el-button
          :disabled="turns.length === 0"
          @click="clearConversation"
        >
          清空会话
        </el-button>
      </template>
    </PageHeader>

    <el-card
      class="ai-qa-view__card liquid-glass-card"
      shadow="never"
    >
      <!-- 会话区:本地维护,每轮独立调用后端单轮接口 -->
      <div
        ref="scrollAnchor"
        class="ai-qa-view__thread"
      >
        <el-empty
          v-if="turns.length === 0"
          description="向苏苏提问运维问题,例如「1 号服务器现在状态如何?最近有没有未读告警?」"
        />

        <div
          v-for="turn in turns"
          :key="turn.id"
          class="ai-qa-view__turn"
        >
          <div class="ai-qa-view__question">
            {{ turn.question }}
          </div>

          <div
            v-loading="turn.pending"
            class="ai-qa-view__answer"
          >
            <template v-if="turn.error !== null">
              <el-alert
                type="error"
                :closable="false"
                show-icon
                :title="turn.error"
              />
            </template>
            <template v-else-if="!turn.pending">
              <div class="ai-qa-view__badges">
                <el-tag
                  v-if="turn.degraded"
                  type="warning"
                  size="small"
                  effect="plain"
                >
                  已降级
                </el-tag>
                <el-tag
                  v-if="!turn.modelUsed"
                  type="info"
                  size="small"
                  effect="plain"
                >
                  确定性摘要
                </el-tag>
                <el-tag
                  v-if="turn.serverId !== null"
                  size="small"
                  effect="plain"
                >
                  服务器 #{{ turn.serverId }}
                </el-tag>
              </div>
              <p class="ai-qa-view__answer-text">
                {{ turn.answer }}
              </p>

              <el-collapse
                v-if="turn.toolCalls.length > 0"
                class="ai-qa-view__tools"
              >
                <el-collapse-item :title="`本轮调用的只读工具(${turn.toolCalls.length})`">
                  <ul class="ai-qa-view__tool-list">
                    <li
                      v-for="(call, index) in turn.toolCalls"
                      :key="index"
                    >
                      <code>{{ call.tool }}</code>
                      <span class="ai-qa-view__tool-args">{{ call.args }}</span>
                    </li>
                  </ul>
                </el-collapse-item>
              </el-collapse>

              <p class="ai-qa-view__meta">
                {{ turn.provider }} / {{ turn.model }}
                <template v-if="turn.usage">
                  · tokens {{ turn.usage.total_tokens }}
                </template>
              </p>
            </template>
          </div>
        </div>
      </div>

      <!-- 输入区 -->
      <div class="ai-qa-view__composer">
        <el-select
          v-model="serverId"
          placeholder="全局提问(可选服务器上下文)"
          clearable
          class="ai-qa-view__server-select"
        >
          <el-option
            v-for="item in serverOptions"
            :key="item.id"
            :label="`${item.name} (#${item.id})`"
            :value="item.id"
          />
        </el-select>
        <el-input
          v-model="question"
          type="textarea"
          :rows="2"
          maxlength="4000"
          show-word-limit
          resize="none"
          placeholder="输入运维问题,Ctrl + Enter 发送"
          @keydown.ctrl.enter.prevent="handleAsk"
        />
        <el-button
          type="primary"
          class="ai-qa-view__ask"
          :loading="pending"
          :disabled="question.trim().length === 0"
          @click="handleAsk"
        >
          提问
        </el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import { describeAiError, requestAiQa } from '@/api/ai'
import { listServers } from '@/api/server'
import type { AiToolCall, AiUsage, Server } from '@/types/api'

/**
 * AI 运维问答页面(F2)。
 *
 * 后端 /api/ai/qa 为无状态单轮接口,本页面在本地维护会话列表;
 * 每次提问独立调用,上下文不会自动带给后端(需在问题中自述)。
 * 服务器上下文为可选参数,省略表示全局性问题。
 */

/** 本地会话中的一轮问答。 */
interface QaTurn {
  id: number
  question: string
  serverId: number | null
  pending: boolean
  answer: string
  toolCalls: AiToolCall[]
  modelUsed: boolean
  degraded: boolean
  provider: string
  model: string
  usage: AiUsage | null
  error: string | null
}

const serverOptions = ref<Server[]>([])
const serverId = ref<number | null>(null)
const question = ref('')
const pending = ref(false)
const turns = ref<QaTurn[]>([])
const scrollAnchor = ref<HTMLElement | null>(null)

let turnSeq = 0

onMounted(async () => {
  try {
    const response = await listServers({ page: 1, page_size: 100, sort_by: 'id', sort_order: 'asc' })
    serverOptions.value = response.data?.items ?? []
  } catch {
    // 服务器下拉加载失败不阻断提问(仍可全局提问)
    serverOptions.value = []
  }
})

onBeforeUnmount(() => {
  pending.value = false
})

async function handleAsk(): Promise<void> {
  const text = question.value.trim()
  if (text.length === 0 || pending.value) return

  const turn: QaTurn = {
    id: ++turnSeq,
    question: text,
    serverId: serverId.value,
    pending: true,
    answer: '',
    toolCalls: [],
    modelUsed: true,
    degraded: false,
    provider: '',
    model: '',
    usage: null,
    error: null
  }
  turns.value.push(turn)
  question.value = ''
  pending.value = true
  await scrollToBottom()

  try {
    const res = await requestAiQa({
      server_id: turn.serverId,
      question: turn.question
    })
    turn.answer = res.data.answer
    turn.toolCalls = res.data.tool_calls
    turn.modelUsed = res.data.model_used
    turn.degraded = res.data.degraded
    turn.provider = res.data.provider
    turn.model = res.data.model
    turn.usage = res.data.usage
  } catch (error) {
    turn.error = describeAiError(error)
    ElMessage.error(turn.error)
  } finally {
    turn.pending = false
    pending.value = false
    await scrollToBottom()
  }
}

function clearConversation(): void {
  turns.value = []
}

async function scrollToBottom(): Promise<void> {
  await nextTick()
  scrollAnchor.value?.scrollTo({ top: scrollAnchor.value.scrollHeight, behavior: 'smooth' })
}
</script>

<style scoped>
.ai-qa-view {
  max-width: 960px;
  margin: 0 auto;
}

.ai-qa-view__card :deep(.el-card__body) {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.ai-qa-view__thread {
  min-height: 320px;
  max-height: 56vh;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding-right: 4px;
}

.ai-qa-view__turn {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.ai-qa-view__question {
  align-self: flex-end;
  max-width: 80%;
  padding: 8px 14px;
  border-radius: 14px 14px 4px 14px;
  background: linear-gradient(135deg, rgba(255, 91, 138, 0.16) 0%, rgba(183, 50, 92, 0.16) 100%);
  font-size: 14px;
  line-height: 1.6;
  color: var(--susu-ink);
  white-space: pre-wrap;
  word-break: break-word;
}

.ai-qa-view__answer {
  align-self: stretch;
  min-height: 40px;
  border-radius: 10px;
  background: var(--susu-surface-soft);
  padding: 10px 14px;
}

.ai-qa-view__badges {
  display: flex;
  gap: 6px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}

.ai-qa-view__answer-text {
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  color: var(--susu-ink);
  white-space: pre-wrap;
  word-break: break-word;
}

.ai-qa-view__tools {
  margin-top: 8px;
  border: none;
}

.ai-qa-view__tool-list {
  margin: 0;
  padding-left: 4px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
}

.ai-qa-view__tool-args {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}

.ai-qa-view__meta {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.ai-qa-view__composer {
  display: flex;
  flex-direction: column;
  gap: 10px;
  border-top: 1px solid rgba(168, 44, 81, 0.12);
  padding-top: 14px;
}

.ai-qa-view__server-select {
  width: 280px;
}

.ai-qa-view__ask {
  align-self: flex-end;
  background: linear-gradient(135deg, #ff5b8a 0%, #b7325c 100%) !important;
  border: none !important;
  box-shadow: 0 6px 14px rgba(255, 91, 138, 0.3) !important;
}

.ai-qa-view__ask:hover {
  background: linear-gradient(135deg, #ff7aa3 0%, #c8426f 100%) !important;
  box-shadow: 0 10px 20px rgba(255, 91, 138, 0.45) !important;
}
</style>
