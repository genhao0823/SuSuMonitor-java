<template>
  <div class="ai-provider-settings-view">
    <PageHeader
      title="AI 设置"
      subtitle="配置你自己的 AI 服务商（OpenAI 兼容）；诊断、问答与命令建议优先使用个人配置，未配置时回退服务器全局配置"
    />

    <el-alert
      v-if="aiDisabled"
      type="warning"
      :closable="false"
      show-icon
      title="AI 功能未开启"
      description="服务端未启用 AI（AI_ENABLED），请联系管理员在服务器配置后使用本页面。"
      class="ai-provider-settings-view__banner"
    />

    <el-card
      v-else
      class="ai-provider-settings-view__card liquid-glass-card"
      shadow="never"
    >
      <div
        v-loading="loading"
        class="ai-provider-settings-view__body"
      >
        <!-- 当前状态概览 -->
        <el-descriptions
          v-if="config && config.configured"
          :column="2"
          size="small"
          border
          class="ai-provider-settings-view__current"
        >
          <el-descriptions-item label="状态">
            <el-tag
              size="small"
              :type="config.enabled ? 'success' : 'info'"
            >
              {{ config.enabled ? '已启用' : '已停用' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="API Key">
            {{ config.api_key_masked ?? '****' }}
          </el-descriptions-item>
          <el-descriptions-item label="服务商">
            {{ config.provider }}
          </el-descriptions-item>
          <el-descriptions-item label="最近更新">
            {{ config.updated_at ?? '—' }}
          </el-descriptions-item>
        </el-descriptions>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          class="ai-provider-settings-view__form"
          @submit.prevent="handleSave"
        >
          <el-form-item
            label="Endpoint（base_url）"
            prop="baseUrl"
          >
            <el-input
              v-model="form.baseUrl"
              placeholder="例如 https://api.deepseek.com/v1"
              clearable
            />
            <span class="ai-provider-settings-view__hint">
              OpenAI 兼容地址（通常含 /v1）；默认仅允许 HTTPS，内网明文网关需服务端放宽
            </span>
          </el-form-item>
          <el-form-item
            label="API Key"
            prop="apiKey"
          >
            <el-input
              v-model="form.apiKey"
              type="password"
              show-password
              :placeholder="apiKeyPlaceholder"
              autocomplete="new-password"
            />
            <span class="ai-provider-settings-view__hint">
              仅保存在服务器数据库（AES-256-GCM 加密），不回显明文；留空表示不修改
            </span>
          </el-form-item>
          <el-form-item
            label="模型"
            prop="model"
          >
            <el-input
              v-model="form.model"
              placeholder="例如 deepseek-chat / gemini-3.8-flash-high"
              clearable
            />
          </el-form-item>
          <el-form-item label="启用个人配置">
            <el-switch v-model="form.enabled" />
            <span class="ai-provider-settings-view__hint">关闭后回退服务器全局配置</span>
          </el-form-item>

          <div class="ai-provider-settings-view__actions">
            <el-button
              :loading="testing"
              :disabled="!canSubmit"
              @click="handleTest"
            >
              {{ testResult === true ? '测试通过' : testResult === false ? '测试失败' : '测试连接' }}
            </el-button>
            <el-button
              type="danger"
              plain
              :disabled="!configured"
              @click="handleClear"
            >
              清除配置
            </el-button>
            <el-button
              type="primary"
              class="ai-provider-settings-view__submit"
              :loading="saving"
              :disabled="!canSubmit"
              @click="handleSave"
            >
              保存
            </el-button>
          </div>
        </el-form>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import {
  deleteMyProviderConfig,
  describeProviderConfigError,
  getMyProviderConfig,
  isAiDisabledError,
  saveMyProviderConfig,
  testMyProviderConfig
} from '@/api/ai-settings'
import type { AiProviderConfigVo } from '@/types/api'

/**
 * 管理员个人 AI 服务商设置页。
 *
 * 个人配置对该管理员的所有 AI 调用（诊断/问答/命令建议）优先生效；
 * api_key 保存后服务端只回掩码，表单留空表示不修改。
 */
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const aiDisabled = ref(false)
const configured = ref(false)
const config = ref<AiProviderConfigVo | null>(null)
const testResult = ref<boolean | null>(null)

const formRef = ref<FormInstance>()
const form = reactive({
  baseUrl: '',
  apiKey: '',
  model: '',
  enabled: true
})

const rules: FormRules = {
  baseUrl: [
    { required: true, message: '请输入 endpoint', trigger: 'blur' },
    {
      validator: (_rule, value: string, cb: (error?: Error) => void) => {
        const text = typeof value === 'string' ? value.trim() : ''
        if (text.length > 0 && !/^https?:\/\//.test(text)) {
          cb(new Error('必须以 http(s):// 开头'))
          return
        }
        cb()
      },
      trigger: 'blur'
    }
  ],
  model: [{ required: true, message: '请输入模型名称', trigger: 'blur' }]
}

const canSubmit = computed<boolean>(
  () => form.baseUrl.trim().length > 0 && form.model.trim().length > 0
)

const apiKeyPlaceholder = computed<string>(() =>
  configured.value && config.value?.api_key_masked
    ? `已保存 ${config.value.api_key_masked}，留空表示不修改`
    : '请输入 API Key'
)

onMounted(() => {
  void loadConfig()
})

async function loadConfig(): Promise<void> {
  loading.value = true
  try {
    const res = await getMyProviderConfig()
    config.value = res.data
    configured.value = Boolean(res.data?.configured)
    if (res.data?.configured) {
      form.baseUrl = res.data.base_url ?? ''
      form.model = res.data.model ?? ''
      form.enabled = res.data.enabled !== false
      form.apiKey = ''
    }
  } catch (error) {
    if (isAiDisabledError(error)) {
      aiDisabled.value = true
      return
    }
    ElMessage.error(describeProviderConfigError(error))
  } finally {
    loading.value = false
  }
}

function buildPayload() {
  return {
    base_url: form.baseUrl.trim(),
    api_key: form.apiKey.trim(),
    model: form.model.trim(),
    enabled: form.enabled
  }
}

async function handleSave(): Promise<void> {
  if (!canSubmit.value || saving.value) return
  if (formRef.value !== undefined) {
    try {
      await formRef.value.validate()
    } catch {
      return
    }
  }
  saving.value = true
  try {
    const res = await saveMyProviderConfig(buildPayload())
    config.value = res.data
    configured.value = Boolean(res.data?.configured)
    form.apiKey = ''
    ElMessage.success('AI 配置已保存，立即对你的 AI 调用生效')
  } catch (error) {
    ElMessage.error(describeProviderConfigError(error))
  } finally {
    saving.value = false
  }
}

async function handleTest(): Promise<void> {
  if (!canSubmit.value || testing.value) return
  if (formRef.value !== undefined) {
    try {
      await formRef.value.validate()
    } catch {
      return
    }
  }
  testing.value = true
  testResult.value = null
  try {
    const res = await testMyProviderConfig(buildPayload())
    testResult.value = res.data.ok
    if (res.data.ok) {
      ElMessage.success(`连接成功（${res.data.latency_ms} ms）`)
    } else {
      ElMessage.error(`连接失败：${res.data.message ?? '未知原因'}`)
    }
  } catch (error) {
    testResult.value = false
    ElMessage.error(describeProviderConfigError(error))
  } finally {
    testing.value = false
  }
}

async function handleClear(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '清除后你的 AI 调用将回退服务器全局配置（如未配置则不可用）。确定清除？',
      '清除个人 AI 配置',
      { confirmButtonText: '清除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await deleteMyProviderConfig()
    configured.value = false
    config.value = { configured: false }
    form.baseUrl = ''
    form.model = ''
    form.apiKey = ''
    form.enabled = true
    ElMessage.success('已清除个人配置')
  } catch (error) {
    ElMessage.error(describeProviderConfigError(error))
  }
}
</script>

<style scoped>
.ai-provider-settings-view {
  max-width: 760px;
  margin: 0 auto;
}

.ai-provider-settings-view__banner {
  margin-bottom: 16px;
}

.ai-provider-settings-view__card :deep(.el-card__body) {
  padding: 20px;
}

.ai-provider-settings-view__body {
  min-height: 200px;
}

.ai-provider-settings-view__current {
  margin-bottom: 20px;
}

.ai-provider-settings-view__form {
  max-width: 560px;
}

.ai-provider-settings-view__hint {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.ai-provider-settings-view__actions {
  display: flex;
  gap: 12px;
  margin-top: 8px;
}

.ai-provider-settings-view__submit {
  background: linear-gradient(135deg, #ff5b8a 0%, #b7325c 100%) !important;
  border: none !important;
  box-shadow: 0 6px 14px rgba(255, 91, 138, 0.3) !important;
}

.ai-provider-settings-view__submit:hover {
  background: linear-gradient(135deg, #ff7aa3 0%, #c8426f 100%) !important;
  box-shadow: 0 10px 20px rgba(255, 91, 138, 0.45) !important;
}
</style>
