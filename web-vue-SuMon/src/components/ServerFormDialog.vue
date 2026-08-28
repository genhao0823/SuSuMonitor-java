<template>
  <el-dialog
    :model-value="modelValue"
    :title="isEdit ? '编辑服务器' : '创建服务器'"
    width="560px"
    :close-on-click-modal="false"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
    @close="onClose"
  >
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-position="top"
      class="server-form-dialog__form"
      @submit.prevent="handleSubmit"
    >
      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item
            label="名称"
            prop="name"
          >
            <el-input
              v-model="form.name"
              placeholder="3-50 位字母、数字、下划线"
              maxlength="50"
              clearable
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item
            label="主机地址"
            prop="host"
          >
            <el-input
              v-model="form.host"
              placeholder="IP 或域名"
              clearable
            />
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item
        label="描述"
        prop="description"
      >
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="2"
          placeholder="可选,不超过 200 字符"
          maxlength="200"
          show-word-limit
        />
      </el-form-item>

      <el-divider content-position="left">
        <span class="server-form-dialog__divider-text">SSH 连接</span>
      </el-divider>

      <el-row :gutter="16">
        <el-col :span="14">
          <el-form-item
            label="SSH 主机"
            prop="ssh_host"
          >
            <el-input
              v-model="form.ssh_host"
              placeholder="通常与 host 相同"
              clearable
            />
          </el-form-item>
        </el-col>
        <el-col :span="10">
          <el-form-item
            label="SSH 端口"
            prop="ssh_port"
          >
            <el-input-number
              v-model="form.ssh_port"
              :min="1"
              :max="65535"
              :step="1"
              controls-position="right"
              class="server-form-dialog__port"
            />
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item
        label="SSH 用户名"
        prop="ssh_user"
      >
        <el-input
          v-model="form.ssh_user"
          placeholder="3-50 位"
          maxlength="50"
          clearable
        />
      </el-form-item>

      <el-form-item
        label="认证方式"
        prop="ssh_auth_type"
      >
        <el-radio-group v-model="form.ssh_auth_type">
          <el-radio value="password">
            密码
          </el-radio>
          <el-radio value="private_key">
            私钥
          </el-radio>
        </el-radio-group>
      </el-form-item>

      <el-form-item
        v-if="form.ssh_auth_type === 'password'"
        :label="isEdit ? 'SSH 密码(留空不修改)' : 'SSH 密码'"
        prop="ssh_password"
      >
        <el-input
          v-model="form.ssh_password"
          type="password"
          show-password
          :placeholder="isEdit ? '留空则不修改现有密码' : '8-64 位'"
          autocomplete="new-password"
        />
      </el-form-item>

      <template v-else>
        <el-form-item
          :label="isEdit ? 'SSH 私钥(留空不修改)' : 'SSH 私钥'"
          prop="ssh_private_key"
        >
          <el-input
            v-model="form.ssh_private_key"
            type="textarea"
            :rows="4"
            :placeholder="isEdit ? '留空则不修改现有私钥' : '-----BEGIN OPENSSH PRIVATE KEY----- ...'"
            autocomplete="new-password"
          />
        </el-form-item>
        <el-form-item
          label="私钥口令(可选)"
          prop="ssh_private_key_passphrase"
        >
          <el-input
            v-model="form.ssh_private_key_passphrase"
            type="password"
            show-password
            placeholder="如私钥未加密可留空"
            autocomplete="new-password"
          />
        </el-form-item>
      </template>
    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="submitting"
        class="server-form-dialog__submit"
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
import { createServer, updateServer } from '@/api/server'
import { ErrorCode } from '@/types/error-code'
import type { Server } from '@/types/api'

interface Props {
  modelValue: boolean
  server: Server | null
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  success: []
}>()

const isEdit = computed<boolean>(() => props.server !== null)

const formRef = ref<FormInstance>()
const submitting = ref(false)

/**
 * 表单本地状态。所有凭据字段初始为空,创建模式必填;
 * 编辑模式从 props.server 预填(凭据保持空 → 后端保留旧值)。
 */
const form = reactive({
  name: '',
  host: '',
  description: '',
  ssh_host: '',
  ssh_port: 22,
  ssh_user: '',
  ssh_auth_type: 'password' as 'password' | 'private_key',
  ssh_password: '',
  ssh_private_key: '',
  ssh_private_key_passphrase: ''
})

/**
 * 当 dialog 打开且传入新的 server 时,同步表单字段。
 */
watch(
  () => [props.modelValue, props.server] as const,
  ([visible]) => {
    if (!visible) {
      return
    }
    resetForm()
    const s = props.server
    if (s !== null) {
      form.name = s.name
      form.host = s.host
      form.description = s.description ?? ''
      form.ssh_host = s.ssh_host
      form.ssh_port = s.ssh_port
      form.ssh_user = s.ssh_user
      form.ssh_auth_type = s.ssh_auth_type
      // 凭据字段保持空 → 后端保留旧值
    }
  },
  { immediate: true }
)

function resetForm(): void {
  form.name = ''
  form.host = ''
  form.description = ''
  form.ssh_host = ''
  form.ssh_port = 22
  form.ssh_user = ''
  form.ssh_auth_type = 'password'
  form.ssh_password = ''
  form.ssh_private_key = ''
  form.ssh_private_key_passphrase = ''
}

/**
 * 必填规则。创建模式下密码/私钥必填;编辑模式(凭据留空=不改)允许空。
 */
const rules: FormRules = {
  name: [
    { required: true, message: '请输入服务器名称', trigger: 'blur' },
    { min: 3, max: 50, message: '名称长度 3 到 50', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_]+$/,
      message: '仅允许字母、数字、下划线',
      trigger: 'blur'
    }
  ],
  host: [{ required: true, message: '请输入主机地址', trigger: 'blur' }],
  ssh_host: [{ required: true, message: '请输入 SSH 主机', trigger: 'blur' }],
  ssh_port: [
    { required: true, message: '请输入 SSH 端口', trigger: 'blur' },
    {
      validator: (_rule, value, cb) => {
        if (typeof value !== 'number' || value < 1 || value > 65535) {
          cb(new Error('端口范围 1-65535'))
        } else {
          cb()
        }
      },
      trigger: 'blur'
    }
  ],
  ssh_user: [
    { required: true, message: '请输入 SSH 用户名', trigger: 'blur' },
    { min: 3, max: 50, message: '用户名长度 3 到 50', trigger: 'blur' }
  ],
  ssh_auth_type: [
    { required: true, message: '请选择认证方式', trigger: 'change' }
  ],
  ssh_password: [
    {
      validator: (_rule, value, cb) => {
        if (!isEdit.value && (typeof value !== 'string' || value.length === 0)) {
          cb(new Error('请输入 SSH 密码'))
        } else if (typeof value === 'string' && value.length > 0 && (value.length < 8 || value.length > 64)) {
          cb(new Error('密码长度 8 到 64'))
        } else {
          cb()
        }
      },
      trigger: 'blur'
    }
  ],
  ssh_private_key: [
    {
      validator: (_rule, value, cb) => {
        if (!isEdit.value && (typeof value !== 'string' || value.length === 0)) {
          cb(new Error('请输入 SSH 私钥'))
        } else {
          cb()
        }
      },
      trigger: 'blur'
    }
  ]
}

/**
 * 把 ApiBusinessError 翻译成用户可读提示。
 */
function explainError(error: unknown): string {
  if (error instanceof ApiBusinessError) {
    if (error.code === ErrorCode.RESOURCE_CONFLICT) {
      return '该 host 已被占用,请使用其他主机地址'
    }
    if (error.code === ErrorCode.INVALID_REQUEST_PARAMETER) {
      return '参数不合法,请检查表单字段'
    }
    if (error.code === ErrorCode.RESOURCE_NOT_FOUND) {
      return '服务器不存在或已被删除'
    }
    return error.message || '操作失败'
  }
  return '网络异常,请稍后重试'
}

async function handleSubmit(): Promise<void> {
  if (formRef.value === undefined) {
    return
  }
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    if (isEdit.value && props.server !== null) {
      const id = props.server.id
      const body: Record<string, unknown> = {
        name: form.name,
        host: form.host,
        description: form.description || null,
        ssh_host: form.ssh_host,
        ssh_port: form.ssh_port,
        ssh_user: form.ssh_user,
        ssh_auth_type: form.ssh_auth_type
      }
      // 凭据字段:空字符串不发,让后端保留旧值
      if (form.ssh_password.length > 0) {
        body.ssh_password = form.ssh_password
      }
      if (form.ssh_private_key.length > 0) {
        body.ssh_private_key = form.ssh_private_key
      }
      if (form.ssh_private_key_passphrase.length > 0) {
        body.ssh_private_key_passphrase = form.ssh_private_key_passphrase
      }
      await updateServer(id, body)
      ElMessage.success('服务器已更新')
    } else {
      await createServer({
        name: form.name,
        host: form.host,
        description: form.description || null,
        ssh_host: form.ssh_host,
        ssh_port: form.ssh_port,
        ssh_user: form.ssh_user,
        ssh_auth_type: form.ssh_auth_type,
        ssh_password:
          form.ssh_auth_type === 'password' ? form.ssh_password : undefined,
        ssh_private_key:
          form.ssh_auth_type === 'private_key' ? form.ssh_private_key : undefined,
        ssh_private_key_passphrase:
          form.ssh_auth_type === 'private_key'
            ? form.ssh_private_key_passphrase
            : undefined
      })
      ElMessage.success('服务器已创建')
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
.server-form-dialog__form {
  padding: 8px 4px 0;
}

.server-form-dialog__form :deep(.el-form-item__label) {
  color: #2a1626;
  font-weight: 600;
}

.server-form-dialog__form :deep(.el-input__wrapper) {
  background: rgba(255, 255, 255, 0.85);
}

.server-form-dialog__port {
  width: 100%;
}

.server-form-dialog__divider-text {
  font-size: 12px;
  font-weight: 600;
  color: #b7325c;
  letter-spacing: 1.5px;
}

.server-form-dialog__submit {
  background: linear-gradient(135deg, #ff5b8a 0%, #b7325c 100%) !important;
  border: none !important;
  box-shadow: 0 6px 14px rgba(255, 91, 138, 0.3) !important;
}

.server-form-dialog__submit:hover {
  background: linear-gradient(135deg, #ff7aa3 0%, #c8426f 100%) !important;
  box-shadow: 0 10px 20px rgba(255, 91, 138, 0.45) !important;
}
</style>