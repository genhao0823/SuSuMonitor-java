import { describe, it, expect, beforeEach, vi } from 'vitest'
import { h, nextTick, type SetupContext } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import { ElMessage, ElMessageBox } from 'element-plus'
import AiProviderSettingsView from '@/views/AiProviderSettingsView.vue'
import {
  deleteMyProviderConfig,
  getMyProviderConfig,
  isAiDisabledError,
  saveMyProviderConfig,
  testMyProviderConfig
} from '@/api/ai-settings'
import { ApiBusinessError } from '@/api/client'
import { ErrorCode } from '@/types/error-code'

/**
 * AiProviderSettingsView 关键交互回归：
 * - 加载：未开启（40400）渲染警示条；已配置时回填 base_url/model/enabled
 * - 保存：留空 api_key 不清空已存 Key（空白原样传给后端判定）
 * - 清除：二次确认后调用删除并重置表单
 */

vi.mock('@/api/ai-settings', () => ({
  getMyProviderConfig: vi.fn(),
  saveMyProviderConfig: vi.fn(),
  deleteMyProviderConfig: vi.fn(),
  testMyProviderConfig: vi.fn(),
  describeProviderConfigError: vi.fn(() => '配置操作失败'),
  isAiDisabledError: vi.fn(() => false)
}))

const getMock = vi.mocked(getMyProviderConfig)
const saveMock = vi.mocked(saveMyProviderConfig)
const deleteMock = vi.mocked(deleteMyProviderConfig)
const isDisabledMock = vi.mocked(isAiDisabledError)

const globalStubs = {
  'el-card': { template: '<div class="el-card-stub"><slot /></div>' },
  'el-alert': {
    props: { title: { type: String, default: '' }, description: { type: String, default: '' } },
    template:
      '<div class="el-alert-stub"><span class="el-alert-stub__title">{{ title }}</span>' +
      '<span class="el-alert-stub__desc">{{ description }}</span></div>'
  },
  'el-descriptions': { template: '<div class="el-descriptions-stub"><slot /></div>' },
  'el-descriptions-item': { template: '<div class="el-descriptions-item-stub"><slot /></div>' },
  'el-form': {
    props: { model: { type: Object, default: undefined } },
    setup(_props: unknown, { expose, slots }: SetupContext) {
      expose({ validate: () => Promise.resolve(true) })
      return () => h('form', { class: 'el-form-stub' }, slots.default?.())
    }
  },
  'el-form-item': { template: '<div class="el-form-item-stub"><slot /></div>' },
  'el-input': {
    props: { modelValue: { type: String, default: '' }, type: { type: String, default: 'text' } },
    emits: ['update:modelValue'],
    template:
      '<input class="el-input-stub" :value="modelValue" ' +
      '@input="$emit(\'update:modelValue\', $event.target.value)" />'
  },
  'el-switch': {
    props: { modelValue: { type: Boolean, default: false } },
    emits: ['update:modelValue'],
    template:
      '<button class="el-switch-stub" @click="$emit(\'update:modelValue\', !modelValue)"></button>'
  },
  'el-tag': { template: '<span class="el-tag-stub"><slot /></span>' },
  'el-button': {
    emits: ['click'],
    template: '<button class="el-button-stub" @click="$emit(\'click\')"><slot /></button>'
  }
}

async function flush(): Promise<void> {
  await nextTick()
  await Promise.resolve()
}

async function mountView(): Promise<VueWrapper> {
  const wrapper = mount(AiProviderSettingsView, {
    global: { stubs: globalStubs, directives: { loading: {} } }
  })
  await flush()
  await flush()
  return wrapper
}

describe('AiProviderSettingsView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    isDisabledMock.mockReturnValue(false)
    vi.spyOn(ElMessage, 'success').mockImplementation((() => ({})) as never)
    vi.spyOn(ElMessage, 'error').mockImplementation((() => ({})) as never)
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    getMock.mockResolvedValue({
      code: 0,
      message: 'success',
      data: {
        configured: true,
        provider: 'openai-compatible',
        base_url: 'https://api.example.test/v1',
        model: 'test-model',
        api_key_masked: 'sk-****9876',
        enabled: true,
        updated_at: '2026-09-09T12:00:00'
      }
    })
  })

  it('AI 未开启（40400）时渲染警示条而非表单', async () => {
    getMock.mockRejectedValue(new ApiBusinessError(ErrorCode.RESOURCE_NOT_FOUND, 'not found'))
    isDisabledMock.mockReturnValue(true)
    const wrapper = await mountView()
    expect(wrapper.find('.el-alert-stub__title').text()).toBe('AI 功能未开启')
    expect(wrapper.find('.el-form-stub').exists()).toBe(false)
  })

  it('已配置时回填 base_url/model，api_key 保持空白（留空=不修改）', async () => {
    const wrapper = await mountView()
    const inputs = wrapper.findAll('.el-input-stub')
    expect((inputs[0].element as HTMLInputElement).value).toBe('https://api.example.test/v1')
    expect((inputs[1].element as HTMLInputElement).value).toBe('')
    expect((inputs[2].element as HTMLInputElement).value).toBe('test-model')
  })

  it('保存成功：调用 PUT 并提示；api_key 空白原样传递', async () => {
    saveMock.mockResolvedValue({
      code: 0,
      message: 'success',
      data: { configured: true, enabled: true }
    })
    const wrapper = await mountView()
    await wrapper.find('.el-input-stub').setValue('https://new.example.test/v1')
    const saveButton = wrapper
      .findAll('.el-button-stub')
      .find((b) => b.text().includes('保存'))
    await saveButton?.trigger('click')
    await flush()
    expect(saveMock).toHaveBeenCalledWith({
      base_url: 'https://new.example.test/v1',
      api_key: '',
      model: 'test-model',
      enabled: true
    })
    expect(ElMessage.success).toHaveBeenCalled()
  })

  it('清除配置：经二次确认后调用 DELETE 并重置', async () => {
    deleteMock.mockResolvedValue({ code: 0, message: 'success', data: true })
    const wrapper = await mountView()
    const clearButton = wrapper
      .findAll('.el-button-stub')
      .find((b) => b.text().includes('清除配置'))
    await clearButton?.trigger('click')
    await flush()
    expect(ElMessageBox.confirm).toHaveBeenCalled()
    expect(deleteMock).toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('https://api.example.test/v1')
  })

  it('连通性测试按钮：展示测试通过', async () => {
    vi.mocked(testMyProviderConfig).mockResolvedValue({
      code: 0,
      message: 'success',
      data: { ok: true, latency_ms: 99, error_code: null, message: null }
    })
    const wrapper = await mountView()
    const testButton = wrapper
      .findAll('.el-button-stub')
      .find((b) => b.text().includes('测试连接'))
    await testButton?.trigger('click')
    await flush()
    expect(ElMessage.success).toHaveBeenCalledWith(expect.stringContaining('连接成功'))
  })
})
