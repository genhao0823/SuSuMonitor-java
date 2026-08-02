<template>
  <el-dialog
    :model-value="modelValue"
    :title="isEdit ? '编辑告警规则' : '新建告警规则'"
    width="540px"
    :close-on-click-modal="false"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
    @close="onClose"
  >
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-position="top"
      class="alert-rule-dialog__form"
      @submit.prevent="handleSubmit"
    >
      <el-form-item
        label="适用服务器"
        prop="server_id"
      >
        <el-select
          v-model="serverIdInput"
          placeholder="不选则创建全局规则"
          clearable
          class="alert-rule-dialog__full"
          :disabled="isEdit"
        >
          <el-option
            v-for="item in serverOptions"
            :key="item.id"
            :label="`${item.name} (#${item.id})`"
            :value="item.id"
          />
        </el-select>
        <span
          v-if="isEdit"
          class="alert-rule-dialog__hint"
        >
          编辑模式下不可修改适用服务器
        </span>
      </el-form-item>

      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item
            label="指标"
            prop="metric"
          >
            <el-select
              v-model="form.metric"
              :disabled="isEdit"
              class="alert-rule-dialog__full"
            >
              <el-option
                v-for="m in METRIC_OPTIONS"
                :key="m"
                :label="metricLabel(m)"
                :value="m"
              />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item
            label="比较运算符"
            prop="operator"
          >
            <el-select
              v-model="form.operator"
              :disabled="isEdit"
              class="alert-rule-dialog__full"
            >
              <el-option
                v-for="o in OPERATOR_OPTIONS"
                :key="o"
                :label="o"
                :value="o"
              />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>

      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item
            label="阈值"
            prop="threshold_value"
          >
            <el-input-number
              v-model="form.threshold_value"
              :min="0"
              :step="1"
              controls-position="right"
              class="alert-rule-dialog__full"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item
            label="等级"
            prop="level"
          >
            <el-select
              v-model="form.level"
              class="alert-rule-dialog__full"
            >
              <el-option
                v-for="l in LEVEL_OPTIONS"
                :key="l"
                :label="levelLabel(l)"
                :value="l"
              />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item
        label="连续确认次数"
        prop="confirm_count"
      >
        <div class="alert-rule-dialog__confirm">
          <el-input-number
            v-model="form.confirm_count"
            :min="1"
            :max="100"
            :step="1"
            controls-position="right"
            class="alert-rule-dialog__confirm-input"
          />
          <span class="alert-rule-dialog__confirm-tip">
            {{ form.confirm_count > 1 ? `连续越界 ${form.confirm_count} 次才触发(防瞬时抖动)` : '首次越界即触发' }}
          </span>
        </div>
      </el-form-item>

      <el-form-item
        v-if="isEdit"
        label="启用"
        prop="enabled"
      >
        <el-switch v-model="form.enabled" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="submitting"
        class="alert-rule-dialog__submit"
        @click="handleSubmit"
      >
        {{ isEdit ? '保存' : '创建' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { ApiBusinessError } from '@/api/client'
import { useAlertsStore } from '@/stores/alerts'
import { ErrorCode } from '@/types/error-code'
import type {
  AlertLevel,
  AlertMetric,
  AlertOperator,
  AlertRule,
  Server
} from '@/types/api'

interface Props {
  modelValue: boolean
  rule: AlertRule | null
  serverOptions: Server[]
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  success: []
}>()

const alerts = useAlertsStore()

const isEdit = computed<boolean>(() => props.rule !== null)

const METRIC_OPTIONS: AlertMetric[] = ['cpu', 'memory', 'disk', 'temperature', 'load']
const OPERATOR_OPTIONS: AlertOperator[] = ['>', '>=', '<', '<=']
const LEVEL_OPTIONS: AlertLevel[] = ['warning', 'critical']

function metricLabel(m: AlertMetric): string {
  switch (m) {
    case 'cpu':
      return 'CPU 使用率'
    case 'memory':
      return '内存使用率'
    case 'disk':
      return '磁盘使用率'
    case 'temperature':
      return '温度'
    case 'load':
      return '系统负载'
  }
}
function levelLabel(l: AlertLevel): string {
  return l === 'critical' ? '严重' : '警告'
}

const formRef = ref<FormInstance>()
const submitting = ref(false)

/**
 * 表单本地状态。server_id 单独用 serverIdInput(可为 null 表"全局")。
 * 编辑模式下 metric/operator/serverIdInput 锁定,不允许改动。
 */
const form = reactive({
  metric: 'cpu' as AlertMetric,
  operator: '>' as AlertOperator,
  threshold_value: 80,
  level: 'warning' as AlertLevel,
  confirm_count: 1,
  enabled: true
})
const serverIdInput = ref<number | null>(null)

watch(
  () => [props.modelValue, props.rule] as const,
  ([visible]) => {
    if (!visible) return
    resetForm()
    const r = props.rule
    if (r !== null) {
      serverIdInput.value = r.server_id
      form.metric = (r.metric as AlertMetric) ?? 'cpu'
      form.operator = (r.operator as AlertOperator) ?? '>'
      form.threshold_value = r.threshold_value
      form.level = (r.level as AlertLevel) ?? 'warning'
      form.confirm_count = r.confirm_count ?? 1
      form.enabled = r.enabled
    }
  },
  { immediate: true }
)

function resetForm(): void {
  serverIdInput.value = null
  form.metric = 'cpu'
  form.operator = '>'
  form.threshold_value = 80
  form.level = 'warning'
  form.confirm_count = 1
  form.enabled = true
}

const rules: FormRules = {
  metric: [{ required: true, message: '请选择指标', trigger: 'change' }],
  operator: [{ required: true, message: '请选择运算符', trigger: 'change' }],
  level: [{ required: true, message: '请选择等级', trigger: 'change' }],
  threshold_value: [
    {
      validator: (_rule, value, cb) => {
        if (typeof value !== 'number' || Number.isNaN(value) || value < 0) {
          cb(new Error('阈值必须 ≥ 0'))
        } else {
          cb()
        }
      },
      trigger: 'blur'
    }
  ]
}

function explainError(error: unknown): string {
  if (error instanceof ApiBusinessError) {
    if (error.code === ErrorCode.INVALID_REQUEST_PARAMETER) {
      return '参数不合法,请检查表单字段'
    }
    if (error.code === ErrorCode.RESOURCE_CONFLICT) {
      return '规则冲突:同一指标 / 服务器的同类规则已存在'
    }
    if (error.code === ErrorCode.RESOURCE_NOT_FOUND) {
      return '服务器不存在或已被删除'
    }
    if (error.code === ErrorCode.FORBIDDEN) {
      return '无权限:仅管理员可管理告警规则'
    }
    return error.message || '操作失败'
  }
  return '网络异常,请稍后重试'
}

async function handleSubmit(): Promise<void> {
  if (formRef.value === undefined) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    // store 失败时**重抛** ApiBusinessError,直接落入下方 catch + explainError
    // 此处无需 null 检查,也不显示通用文案
    if (isEdit.value && props.rule !== null) {
      await alerts.updateRule(props.rule.id, {
        threshold_value: form.threshold_value,
        level: form.level,
        enabled: form.enabled,
        confirm_count: form.confirm_count
      })
      ElMessage.success('规则已更新')
    } else {
      await alerts.createRule({
        server_id: serverIdInput.value,
        metric: form.metric,
        operator: form.operator,
        threshold_value: form.threshold_value,
        level: form.level,
        confirm_count: form.confirm_count
      })
      ElMessage.success('规则已创建')
    }
    emit('success')
    emit('update:modelValue', false)
  } catch (error) {
    ElMessage.error(explainError(error))
  } finally {
    submitting.value = false
  }
}

function onClose(): void {
  resetForm()
}
</script>

<style scoped>
.alert-rule-dialog__form {
  padding: 8px 4px 0;
}

.alert-rule-dialog__form :deep(.el-form-item__label) {
  color: #2a1626;
  font-weight: 600;
}

.alert-rule-dialog__full {
  width: 100%;
}

.alert-rule-dialog__confirm {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
}

.alert-rule-dialog__confirm-input {
  width: 160px;
}

.alert-rule-dialog__confirm-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.alert-rule-dialog__hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-left: 8px;
}

.alert-rule-dialog__submit {
  background: linear-gradient(135deg, #ff5b8a 0%, #b7325c 100%) !important;
  border: none !important;
  box-shadow: 0 6px 14px rgba(255, 91, 138, 0.3) !important;
}

.alert-rule-dialog__submit:hover {
  background: linear-gradient(135deg, #ff7aa3 0%, #c8426f 100%) !important;
  box-shadow: 0 10px 20px rgba(255, 91, 138, 0.45) !important;
}
</style>