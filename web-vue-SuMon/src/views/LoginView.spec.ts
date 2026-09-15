import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type * as ElementPlus from 'element-plus'
import { ApiBusinessError } from '@/api/client'
import { ErrorCode } from '@/types/error-code'
import * as authApi from '@/api/auth'
import LoginView from '@/views/LoginView.vue'

const { pushSpy, messageErrorSpy, messageSuccessSpy } = vi.hoisted(() => ({
  pushSpy: vi.fn(),
  messageErrorSpy: vi.fn(),
  messageSuccessSpy: vi.fn()
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ push: pushSpy })
}))

// 与 auth.spec.ts 相同的工厂形态:store 与 LoginView 会解构全部导出,缺一会破坏导入。
// getBootstrapStatus 默认返回非 pending,初始化引导条仅在显式用例中开启。
vi.mock('@/api/auth', () => ({
  loginUser: vi.fn(),
  registerUser: vi.fn(),
  getCurrentUser: vi.fn(),
  logoutUser: vi.fn(),
  getBootstrapStatus: vi.fn()
}))

vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof ElementPlus>('element-plus')
  return {
    ...actual,
    ElMessage: {
      success: messageSuccessSpy,
      warning: vi.fn(),
      info: vi.fn(),
      error: messageErrorSpy
    }
  }
})

// el-input/el-checkbox 以受控 stub 形式挂载,保留 v-model 双向语义;
// AuthLayout 透传 default slot,登录表单直接可见。
const globalStubs = {
  AuthLayout: { template: '<div><slot /></div>' },
  RouterLink: { template: '<a><slot /></a>' },
  ElForm: { template: '<form><slot /></form>' },
  ElFormItem: { template: '<div><slot /></div>' },
  ElInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
  },
  ElCheckbox: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template:
      '<input type="checkbox" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" />'
  },
  ElAlert: { template: '<div class="el-alert-stub"><slot name="title" /><slot name="description" /></div>' }
}

async function mountLoginView(): Promise<VueWrapper> {
  const wrapper = mount(LoginView, {
    global: {
      plugins: [createPinia()],
      stubs: globalStubs
    }
  })
  await flushPromises()
  return wrapper
}

async function submitLogin(wrapper: VueWrapper): Promise<void> {
  const inputs = wrapper.findAll('input')
  await inputs[0].setValue('susu')
  await inputs[1].setValue('wrong-password')
  await wrapper.find('button.login-view__submit').trigger('click')
  await flushPromises()
}

/**
 * LoginView 单测:锁定联调观察项 B2-1 的"登录失败仅一条提示"语义,
 * 并守护成功路径的跳转行为。
 */
describe('LoginView / 登录失败单提示', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    pushSpy.mockReset()
    messageErrorSpy.mockReset()
    messageSuccessSpy.mockReset()
    vi.mocked(authApi.loginUser).mockReset()
    // 每个用例默认非 pending;pending 场景用例自行覆盖返回值。
    vi.mocked(authApi.getBootstrapStatus).mockResolvedValue({
      code: 0,
      message: 'success',
      data: { bootstrapPending: false }
    })
  })

  it('pending 状态下登录页顶部展示初始化引导条', async () => {
    vi.mocked(authApi.getBootstrapStatus).mockResolvedValue({
      code: 0,
      message: 'success',
      data: { bootstrapPending: true }
    })
    const wrapper = await mountLoginView()
    expect(wrapper.find('.login-view__bootstrap-alert').exists()).toBe(true)
    wrapper.unmount()
  })

  it('非 pending 状态下不展示初始化引导条', async () => {
    const wrapper = await mountLoginView()
    expect(wrapper.find('.login-view__bootstrap-alert').exists()).toBe(false)
    wrapper.unmount()
  })

  it('登录失败(40001)仅弹一条"用户名或密码错误",且不发生跳转', async () => {
    vi.mocked(authApi.loginUser).mockRejectedValueOnce(
      new ApiBusinessError(ErrorCode.INVALID_USERNAME_OR_PASSWORD, 'invalid username or password')
    )
    const wrapper = await mountLoginView()
    await submitLogin(wrapper)
    expect(authApi.loginUser).toHaveBeenCalledTimes(1)
    expect(messageErrorSpy).toHaveBeenCalledTimes(1)
    expect(messageErrorSpy).toHaveBeenCalledWith('用户名或密码错误')
    expect(pushSpy).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('待审核账户(40300)提示审核中文案,同样仅一条', async () => {
    vi.mocked(authApi.loginUser).mockRejectedValueOnce(
      new ApiBusinessError(ErrorCode.FORBIDDEN, 'forbidden')
    )
    const wrapper = await mountLoginView()
    await submitLogin(wrapper)
    expect(messageErrorSpy).toHaveBeenCalledTimes(1)
    expect(messageErrorSpy).toHaveBeenCalledWith('账户尚未通过审核,无法登录')
    wrapper.unmount()
  })

  it('登录成功弹一次成功提示并跳转 /dashboard', async () => {
    vi.mocked(authApi.loginUser).mockResolvedValueOnce({
      code: 0,
      message: 'success',
      data: {
        token: 'jwt-token',
        tokenType: 'Bearer',
        expiresIn: 259200,
        user: {
          id: 1,
          username: 'susu',
          role: 'admin',
          reviewStatus: 'approved',
          reviewedAt: null,
          createdAt: '2026-01-01'
        }
      }
    })
    const wrapper = await mountLoginView()
    await submitLogin(wrapper)
    expect(messageSuccessSpy).toHaveBeenCalledTimes(1)
    expect(pushSpy).toHaveBeenCalledWith('/dashboard')
    expect(messageErrorSpy).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
