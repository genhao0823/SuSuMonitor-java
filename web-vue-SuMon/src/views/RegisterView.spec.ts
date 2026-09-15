import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type * as ElementPlus from 'element-plus'
import { ApiBusinessError } from '@/api/client'
import { ErrorCode } from '@/types/error-code'
import * as authApi from '@/api/auth'
import RegisterView from '@/views/RegisterView.vue'

const { pushSpy, messageErrorSpy, messageSuccessSpy } = vi.hoisted(() => ({
  pushSpy: vi.fn(),
  messageErrorSpy: vi.fn(),
  messageSuccessSpy: vi.fn()
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({}),
  useRouter: () => ({ push: pushSpy })
}))

// 与 LoginView.spec 相同的工厂形态:RegisterView 解构本文件导出,缺一会破坏导入。
vi.mock('@/api/auth', () => ({
  loginUser: vi.fn(),
  registerUser: vi.fn(),
  getCurrentUser: vi.fn(),
  logoutUser: vi.fn(),
  getBootstrapStatus: vi.fn()
}))

// auth store 的 register 由本文件 mock 替身接管。
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    register: vi.fn().mockImplementation((...args: unknown[]) => registerSpy(args[0]))
  })
}))

const { registerSpy } = vi.hoisted(() => ({ registerSpy: vi.fn().mockResolvedValue(undefined) }))

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

// el-input 以受控 stub 形式挂载,保留 v-model 双向语义;AuthLayout 透传 default slot。
// ElForm stub 暴露 validate 方法:RegisterView 提交前会 await formRef.validate()。
const globalStubs = {
  AuthLayout: { template: '<div><slot /></div>' },
  RouterLink: { template: '<a><slot /></a>' },
  ElForm: {
    template: '<form><slot /></form>',
    methods: { validate: (): Promise<boolean> => Promise.resolve(true) }
  },
  ElFormItem: { template: '<div><slot /></div>' },
  ElInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
  }
}

async function mountRegisterView(): Promise<VueWrapper> {
  const wrapper = mount(RegisterView, {
    global: {
      plugins: [createPinia()],
      stubs: globalStubs
    }
  })
  await flushPromises()
  return wrapper
}

/**
 * RegisterView 单测:锁定批次 8 的初始化令牌条件渲染与提交语义——
 * pending 时令牌必填并随注册请求携带;非 pending 时请求体与历史一致。
 */
describe('RegisterView / 初始化令牌', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    pushSpy.mockReset()
    messageErrorSpy.mockReset()
    messageSuccessSpy.mockReset()
    registerSpy.mockReset()
    registerSpy.mockResolvedValue(undefined)
    vi.mocked(authApi.registerUser).mockReset()
  })

  it('非 pending 时不渲染令牌输入框,注册请求体不含 bootstrapToken', async () => {
    vi.mocked(authApi.getBootstrapStatus).mockResolvedValue({
      code: 0,
      message: 'success',
      data: { bootstrapPending: false }
    })
    const wrapper = await mountRegisterView()
    const inputs = wrapper.findAll('input')
    expect(inputs.length).toBe(3)
    await inputs[0].setValue('susu')
    await inputs[1].setValue('Password123')
    await inputs[2].setValue('Password123')
    await wrapper.find('button.register-view__submit').trigger('click')
    await flushPromises()
    expect(registerSpy).toHaveBeenCalledTimes(1)
    expect(registerSpy.mock.calls[0][0]).toEqual({ username: 'susu', password: 'Password123' })
    expect(messageSuccessSpy).toHaveBeenCalledTimes(1)
    expect(pushSpy).toHaveBeenCalledWith({ name: 'login' })
    wrapper.unmount()
  })

  it('pending 时渲染令牌输入框并随注册请求携带', async () => {
    vi.mocked(authApi.getBootstrapStatus).mockResolvedValue({
      code: 0,
      message: 'success',
      data: { bootstrapPending: true }
    })
    const wrapper = await mountRegisterView()
    const inputs = wrapper.findAll('input')
    expect(inputs.length).toBe(4)
    await inputs[0].setValue('admin')
    await inputs[1].setValue('Password123')
    await inputs[2].setValue('TOKEN_VALUE_32_CHARS_MINIMUM_AAAAAAAA')
    await inputs[3].setValue('Password123')
    await wrapper.find('button.register-view__submit').trigger('click')
    await flushPromises()
    expect(registerSpy).toHaveBeenCalledTimes(1)
    expect(registerSpy.mock.calls[0][0]).toEqual({
      username: 'admin',
      password: 'Password123',
      bootstrapToken: 'TOKEN_VALUE_32_CHARS_MINIMUM_AAAAAAAA'
    })
    wrapper.unmount()
  })

  it('注册返回 40311 时提示令牌无效文案且不跳转', async () => {
    vi.mocked(authApi.getBootstrapStatus).mockResolvedValue({
      code: 0,
      message: 'success',
      data: { bootstrapPending: true }
    })
    registerSpy.mockRejectedValueOnce(
      new ApiBusinessError(ErrorCode.AUTH_BOOTSTRAP_TOKEN_INVALID, 'bootstrap token invalid')
    )
    const wrapper = await mountRegisterView()
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('admin')
    await inputs[1].setValue('Password123')
    await inputs[2].setValue('WRONG_TOKEN_32_CHARS_MINIMUM_AAAAAAAAAA')
    await inputs[3].setValue('Password123')
    await wrapper.find('button.register-view__submit').trigger('click')
    await flushPromises()
    expect(messageErrorSpy).toHaveBeenCalledWith('初始化令牌无效,请核对服务器启动日志中的令牌')
    expect(pushSpy).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
