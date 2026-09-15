<template>
  <AuthLayout
    stage-tagline="续缘再起,从注册开始"
    :stage-quotes="registerStageQuotes"
    panel-title="创建账户"
    panel-sub="加入 SuSu 监控的运维小队"
    footer-hint="首个注册用户自动 admin/approved"
    hero-image="https://java-ai-genhaosan.oss-cn-beijing.aliyuncs.com/0dc3f6ad-d7df-4e11-a388-8c5f79804c89.jpg"
    hero-image-fallback="/tushansusu-hero.jpg"
    hero-alt="涂山苏苏"
  >
    <template #default>
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="handleSubmit"
      >
        <el-form-item
          label="用户名"
          prop="username"
        >
          <el-input
            v-model="form.username"
            autocomplete="username"
            placeholder="3-50 位字母、数字或下划线"
            :prefix-icon="User"
            clearable
          />
        </el-form-item>
        <el-form-item
          label="密码"
          prop="password"
        >
          <el-input
            v-model="form.password"
            type="password"
            autocomplete="new-password"
            show-password
            placeholder="8-64 位"
            :prefix-icon="Lock"
          />
        </el-form-item>
        <el-form-item
          label="确认密码"
          prop="confirmPassword"
        >
          <el-input
            v-model="form.confirmPassword"
            type="password"
            autocomplete="new-password"
            show-password
            placeholder="请再次输入密码"
            :prefix-icon="Lock"
          />
        </el-form-item>
        <el-button
          type="primary"
          native-type="submit"
          :loading="submitting"
          class="register-view__submit"
          @click="handleSubmit"
        >
          注册
        </el-button>
        <div class="register-view__footer">
          <span>已有账户?</span>
          <router-link :to="{ name: 'login' }">
            返回登录
          </router-link>
        </div>
      </el-form>
    </template>
  </AuthLayout>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import AuthLayout from '@/views/AuthLayout.vue'
import { ApiBusinessError } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { ErrorCode } from '@/types/error-code'

const router = useRouter()

/**
 * 注册页专属引言池。每次进入页面随机抽一句展示,
 * 鼠标悬停或点击可切换到下一句。聚焦"涂山欢迎新人加入"主题。
 */
const registerStageQuotes: string[] = [
  '「你是新来的小道士吗?欢迎加入涂山的守护者」',
  '「续缘之书翻开新页,等你写下自己的名字哦」',
  '「苏苏把你写进了涂山狐妖的小本本里啦~」',
  '「纯爱天光·入籍版:让新账号永远闪亮登场」',
  '「白月初:又来一个比我还能吃的?」',
  '「涂山一脉的狐妖小队,正式+1!」',
  '「苏苏的铃铛已响:欢迎你的到来~」',
  '「小狐狸们会记住每一个新来的名字哦」',
  '「苦情巨树说:这颗缘分,我收下了」',
  '「给新续缘者一张 VIP 入山券,请查收~」',
  '「今天也是结识新朋友的好日子呀~」',
  '「入山第一步:把密码记在小本本里(不是)」',
  '「苏苏提示:请使用 admin 注册审核你的未来同事哦」',
  '「续缘铃响过的地方,新朋友永远欢迎」',
  '「涂山雅雅亲自盖章:这位新人的缘分,圆满了」',
  '「苦情巨树已经把你的名字写进缘簿啦」',
  '「白月初:又注册一个,苏苏的零食费 +1」',
  '「续缘之书翻开新页:第 N 位守护者登场」',
  '「进入涂山需要 3 步:登录、注册、吃糖」',
  '「黑狐:你注册成功就没人入伙到我们这边了?」',
  '「白裘恩:欢迎入伙,记得给小费」',
  '「涂山容容已为你算好命运:必成大器」'
]
const auth = useAuthStore()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({
  username: '',
  password: '',
  confirmPassword: ''
})

function validateConfirmPassword(
  _rule: unknown,
  value: string,
  callback: (err?: Error) => void
): void {
  if (value !== form.password) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 50, message: '用户名长度 3 到 50', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_]+$/,
      message: '仅允许字母、数字、下划线',
      trigger: 'blur'
    }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 64, message: '密码长度 8 到 64', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    { validator: validateConfirmPassword, trigger: 'blur' }
  ]
}

function explainRegisterError(error: unknown): string {
  if (error instanceof ApiBusinessError) {
    if (error.code === ErrorCode.RESOURCE_CONFLICT) {
      return '用户名已被占用'
    }
    if (error.code === ErrorCode.INVALID_REQUEST_PARAMETER) {
      return '用户名或密码不符合要求'
    }
    if (error.code === ErrorCode.INTERNAL_SERVER_ERROR) {
      return '服务器内部错误,请稍后重试'
    }
    return error.message || '注册失败'
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
    await auth.register({
      username: form.username,
      password: form.password
    })
    ElMessage.success('注册成功,请使用新账户登录')
    await router.push({ name: 'login' })
  } catch (error) {
    ElMessage.error(explainRegisterError(error))
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.register-view__submit {
  width: 100%;
  height: 44px;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 8px;
  margin-top: 10px;
  color: #ffffff;
  background: linear-gradient(135deg, #ff6b9d 0%, #f43f7e 55%, #e0356f 100%);
  border: none;
  border-radius: 12px;
  box-shadow: 0 10px 26px rgba(244, 63, 126, 0.35);
  transition: filter 0.15s, transform 0.15s, box-shadow 0.15s;
}

.register-view__submit:hover,
.register-view__submit:focus-visible {
  filter: brightness(1.08);
  transform: translateY(-1px);
  box-shadow: 0 12px 30px rgba(244, 63, 126, 0.45);
}

.register-view__submit:active {
  filter: brightness(0.95);
  transform: translateY(0);
}

.register-view__footer {
  margin-top: 16px;
  text-align: center;
  font-size: 13px;
  color: var(--auth-ink-muted);
}

.register-view__footer a {
  margin-left: 4px;
  color: var(--auth-link);
  text-decoration: none;
  font-weight: 700;
}

.register-view__footer a:hover {
  color: var(--auth-primary-bright);
}
</style>