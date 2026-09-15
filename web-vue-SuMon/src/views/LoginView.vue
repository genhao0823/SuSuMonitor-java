<template>
  <AuthLayout
    stage-tagline="涂山一脉,以萌护网"
    :stage-quotes="loginStageQuotes"
    panel-title="欢迎回来"
    panel-sub="请登录您的 SuSu 监控账户"
    footer-hint="仅 approved 用户可登录"
    hero-image="https://java-ai-genhaosan.oss-cn-beijing.aliyuncs.com/0dc3f6ad-d7df-4e11-a388-8c5f79804c89.jpg"
    hero-image-fallback="/tushansusu-hero.jpg"
    hero-alt="涂山苏苏"
  >
    <template #default>
      <el-alert
        v-if="bootstrapPending"
        type="warning"
        :closable="false"
        show-icon
        title="系统尚未初始化管理员"
        description="请先到注册页,凭服务器启动日志中的一次性初始化令牌完成首管理员注册。"
        class="login-view__bootstrap-alert"
      />
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
            placeholder="请输入用户名"
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
            autocomplete="current-password"
            show-password
            placeholder="请输入密码"
            :prefix-icon="Lock"
          />
        </el-form-item>
        <div class="login-view__options">
          <el-checkbox v-model="rememberMe">
            记住我
          </el-checkbox>
          <a
            href="#"
            class="login-view__forgot"
            @click.prevent="handleForgot"
          >
            忘记密码?
          </a>
        </div>
        <button
          type="button"
          :disabled="submitting"
          class="el-button el-button--primary login-view__submit"
          @click.prevent="onLoginClick"
        >
          <span v-if="submitting">登录中...</span>
          <span v-else>登录</span>
        </button>
        <div class="login-view__footer">
          <span>还没有账户?</span>
          <router-link :to="{ name: 'register' }">
            前往注册
          </router-link>
        </div>
      </el-form>
    </template>
  </AuthLayout>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import AuthLayout from '@/views/AuthLayout.vue'
import { ApiBusinessError } from '@/api/client'
import { getBootstrapStatus } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { ErrorCode } from '@/types/error-code'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

// 首管理员初始化状态:pending 时在表单顶部展示初始化引导(查询失败按 false 降级)。
const bootstrapPending = ref(false)

// 页面挂载时查询初始化状态(批次 8)。
onMounted(async () => {
  try {
    const status = await getBootstrapStatus()
    bootstrapPending.value = status.data?.bootstrapPending === true
  } catch {
    bootstrapPending.value = false
  }
})

/**
 * 登录页专属引言池。每次进入页面随机抽一句展示,
 * 鼠标悬停或点击可切换到下一句。聚焦"涂山苏苏守护服务器"主题。
 */
const loginStageQuotes: string[] = [
  '「续缘之路漫漫,而你的服务器,我来守护」',
  '「苏苏的狐耳一动,BUG 就乖乖跑开啦」',
  '「涂山苏苏,最强运维狐妖!」',
  '「给服务器吃下苦药草,就再也不会发烧啦」',
  '「小道士要注意哦,涂山的狐妖最会照顾机器了」',
  '「纯爱天光·开机版:让宕机永远见不到你」',
  '「只要有人在,涂山就不会让任何一台服务器掉线」',
  '「苏苏告诉你:重启比补丁更甜~」',
  '「续缘不只是人和人,还有你和你的机器」',
  '「把 BUG 都圈进回忆里吧~」',
  '「今天也是守护服务器的好日子呀~」',
  '「苏苏的小心心,已经部署到每个角落啦」',
  '「白月初:苏苏又把生产环境给炸了…」',
  '「苦情巨树看到宕机,都忍不住掉一片叶子」',
  '「一念之差:点击重启,还是点击格式化?」',
  '「续缘铃响过的地方,日志都是甜的」',
  '「狐妖的直觉:那个端口八成又被扫了」',
  '「涂山令牌已下发:这次一定不背锅」',
  '「苦情巨树都为你这条链路连理:健康、稳定、永不掉线」',
  '「续缘之书翻开新页:服务器永远在线」',
  '「一刀修罗场都打不过运维狐妖的纯爱天光」',
  '「白月初:登录成功 = 苏苏今天不用背锅」',
  '「涂山容容的算盘:这次部署稳赚不亏」',
  '「涂山雅雅的寒气:再宕机就让你冷启动」',
  '「傲来国三少爷路过:这套监控看起来还行」',
  '「虚空之泪都修不好的事,那就重启吧」'
]

const formRef = ref<FormInstance>()
const submitting = ref(false)
const rememberMe = ref<boolean>(true)
const form = reactive({
  username: '',
  password: ''
})

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 50, message: '用户名长度 3 到 50', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 1, max: 64, message: '密码长度 1 到 64', trigger: 'blur' }
  ]
}

/**
 * 将登录失败的 ApiBusinessError 翻译为用户可见提示。
 */
function explainLoginError(error: unknown): string {
  if (error instanceof ApiBusinessError) {
    if (error.code === ErrorCode.FORBIDDEN) {
      return '账户尚未通过审核,无法登录'
    }
    if (error.code === ErrorCode.INVALID_USERNAME_OR_PASSWORD) {
      return '用户名或密码错误'
    }
    if (error.code === ErrorCode.INVALID_REQUEST_PARAMETER) {
      return '请求参数不合法'
    }
    return error.message || '登录失败'
  }
  return '网络异常,请稍后重试'
}

/**
 * 解析登录成功后的跳转目标,仅接受站内相对路径以避免开放重定向。
 */
function resolveRedirect(): string {
  const raw = route.query.redirect
  if (typeof raw !== 'string' || raw.length === 0 || raw[0] !== '/') {
    return '/dashboard'
  }
  return raw
}

async function handleSubmit(): Promise<void> {
  submitting.value = true
  try {
    await auth.login(
      { username: form.username, password: form.password },
      { rememberMe: rememberMe.value }
    )
    ElMessage.success('登录成功')
    await router.push(resolveRedirect())
  } catch (error) {
    ElMessage.error(explainLoginError(error))
  } finally {
    submitting.value = false
  }
}

function onLoginClick(): void {
  void handleSubmit()
}

/**
 * 忘记密码入口占位。后端 MVP 暂不提供找回密码能力,
 * 此处仅提示用户联系管理员,后续 MVP 阶段接入。
 */
function handleForgot(): void {
  ElMessage.info('请联系管理员重置密码,后续 MVP 阶段将接入自助找回流程')
}
</script>

<style scoped>
.login-view__bootstrap-alert {
  margin-bottom: 12px;
}

.login-view__options {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 2px 0 6px;
  font-size: 13px;
}

.login-view__options :deep(.el-checkbox__label) {
  color: var(--auth-ink-soft);
  font-weight: 500;
}

.login-view__options :deep(.el-checkbox__inner) {
  background: transparent;
  border-color: rgba(255, 255, 255, 0.35);
}

.login-view__options :deep(.el-checkbox__input.is-checked .el-checkbox__inner) {
  background-color: var(--auth-primary);
  border-color: var(--auth-primary-bright);
}

.login-view__options :deep(.el-checkbox__input.is-checked + .el-checkbox__label) {
  color: var(--auth-ink-soft);
}

.login-view__forgot {
  color: var(--auth-link);
  text-decoration: none;
  font-weight: 600;
  transition: color 0.15s;
}

.login-view__forgot:hover {
  color: var(--auth-primary-bright);
}

.login-view__submit {
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

.login-view__submit:hover {
  filter: brightness(1.08);
  transform: translateY(-1px);
  box-shadow: 0 12px 30px rgba(244, 63, 126, 0.45);
}

.login-view__submit:active {
  filter: brightness(0.95);
  transform: translateY(0);
}

.login-view__submit:disabled {
  opacity: 0.65;
  cursor: not-allowed;
  transform: none;
}

.login-view__footer {
  margin-top: 16px;
  text-align: center;
  font-size: 13px;
  color: var(--auth-ink-muted);
}

.login-view__footer a {
  margin-left: 4px;
  color: var(--auth-link);
  text-decoration: none;
  font-weight: 700;
}

.login-view__footer a:hover {
  color: var(--auth-primary-bright);
}
</style>