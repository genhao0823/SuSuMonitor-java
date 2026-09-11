import { describe, it, expect, beforeEach, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import { ElMessage } from 'element-plus'
import AiDiagnosisDialog from '@/components/AiDiagnosisDialog.vue'
import { requestAiDiagnosis } from '@/api/ai'
import { ApiBusinessError } from '@/api/client'
import { ErrorCode } from '@/types/error-code'
import type { AiDiagnosis } from '@/types/api'

/**
 * AiDiagnosisDialog 关键交互回归。
 *
 * 范围:
 * - 空问题拦截:不发起请求并提示
 * - 提交:以 server_id + question + 默认 history_minutes 调用 POST /api/ai/diagnoses
 * - 降级结果:model_used=false 时渲染确定性摘要提示条
 * - 失败:限流错误经 describeAiError 映射后由 ElMessage 提示
 *
 * 策略:mock @/api/ai,spy ElMessage,stub Element Plus 组件。
 */

vi.mock('@/api/ai', () => ({
  requestAiDiagnosis: vi.fn(),
  describeAiError: vi.fn(() => 'AI 请求过于频繁或今日额度已用尽,请稍后再试')
}))

const requestAiDiagnosisMock = vi.mocked(requestAiDiagnosis)

const makeDiagnosis = (overrides: Partial<AiDiagnosis> = {}): AiDiagnosis => ({
  summary: 'CPU 持续高位的成因为 Java 进程占用。',
  severity: 'warning',
  findings: [],
  evidence: [],
  recommendations: ['检查进程线程栈'],
  limitations: ['仅基于聚合指标'],
  model_used: true,
  provider: 'openai-compatible',
  model: 'deepseek-chat',
  prompt_version: 'ai-diagnosis-v1',
  usage: { input_tokens: 1, output_tokens: 1, total_tokens: 2, estimated_cost: 0, currency: 'USD' },
  ...overrides
})

const globalStubs = {
  'el-dialog': { template: '<div class="el-dialog-stub"><slot /><slot name="footer" /></div>' },
  'el-form': { template: '<form class="el-form-stub"><slot /></form>' },
  'el-form-item': { template: '<div class="el-form-item-stub"><slot /></div>' },
  'el-input': {
    props: { modelValue: { type: String, default: '' }, type: { type: String, default: 'text' } },
    emits: ['update:modelValue'],
    template:
      '<textarea class="el-input-stub" :value="modelValue" ' +
      '@input="$emit(\'update:modelValue\', $event.target.value)" />'
  },
  'el-input-number': {
    props: { modelValue: { type: Number, default: 0 }, min: { type: Number, default: 0 } },
    emits: ['update:modelValue'],
    template:
      '<input class="el-input-number-stub" :value="modelValue" ' +
      '@input="$emit(\'update:modelValue\', Number($event.target.value))" />'
  },
  'el-alert': {
    props: { title: { type: String, default: '' }, description: { type: String, default: '' } },
    template:
      '<div class="el-alert-stub"><span class="el-alert-stub__title">{{ title }}</span>' +
      '<span class="el-alert-stub__desc">{{ description }}</span></div>'
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

async function mountDialog(): Promise<VueWrapper> {
  const wrapper = mount(AiDiagnosisDialog, {
    props: { modelValue: true, serverId: 7, serverName: 'web-1' },
    global: { stubs: globalStubs }
  })
  await flush()
  return wrapper
}

describe('AiDiagnosisDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.spyOn(ElMessage, 'warning').mockImplementation((() => ({})) as never)
    vi.spyOn(ElMessage, 'error').mockImplementation((() => ({})) as never)
    vi.spyOn(ElMessage, 'success').mockImplementation((() => ({})) as never)
  })

  it('空问题点击诊断:提示且不发起请求', async () => {
    const wrapper = await mountDialog()
    const submit = wrapper.findAll('.el-button-stub').find((b) => b.text().includes('开始诊断'))
    await submit?.trigger('click')
    await flush()
    expect(ElMessage.warning).toHaveBeenCalled()
    expect(requestAiDiagnosisMock).not.toHaveBeenCalled()
  })

  it('提交:携带 server_id / question / 默认 history_minutes 调用诊断接口', async () => {
    const wrapper = await mountDialog()
    const textarea = wrapper.find('.el-input-stub')
    await textarea.setValue('为什么 CPU 升高?')
    const submit = wrapper.findAll('.el-button-stub').find((b) => b.text().includes('开始诊断'))
    await submit?.trigger('click')
    await flush()
    expect(requestAiDiagnosisMock).toHaveBeenCalledWith({
      server_id: 7,
      question: '为什么 CPU 升高?',
      history_minutes: 30
    })
  })

  it('降级结果:model_used=false 渲染确定性摘要提示与建议', async () => {
    requestAiDiagnosisMock.mockResolvedValue({
      code: 0,
      message: 'success',
      data: makeDiagnosis({ model_used: false })
    })
    const wrapper = await mountDialog()
    await wrapper.find('.el-input-stub').setValue('看下状态')
    const submit = wrapper.findAll('.el-button-stub').find((b) => b.text().includes('开始诊断'))
    await submit?.trigger('click')
    await flush()
    const alertText = wrapper.find('.el-alert-stub').text()
    expect(alertText).toContain('确定性摘要')
    expect(wrapper.text()).toContain('CPU 持续高位的成因为 Java 进程占用。')
    expect(wrapper.text()).toContain('检查进程线程栈')
  })

  it('限流失败:经 describeAiError 映射后由 ElMessage.error 提示', async () => {
    requestAiDiagnosisMock.mockRejectedValue(
      new ApiBusinessError(ErrorCode.AI_RATE_LIMIT_REACHED, 'AI rate limit reached')
    )
    const wrapper = await mountDialog()
    await wrapper.find('.el-input-stub').setValue('看下状态')
    const submit = wrapper.findAll('.el-button-stub').find((b) => b.text().includes('开始诊断'))
    await submit?.trigger('click')
    await flush()
    expect(ElMessage.error).toHaveBeenCalledWith('AI 请求过于频繁或今日额度已用尽,请稍后再试')
  })
})
