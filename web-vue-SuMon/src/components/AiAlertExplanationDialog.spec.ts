import { describe, it, expect, beforeEach, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import { ElMessage } from 'element-plus'
import AiAlertExplanationDialog from '@/components/AiAlertExplanationDialog.vue'
import { getAlertExplanation } from '@/api/ai'
import type { AiAlertExplanation } from '@/types/api'

/**
 * AiAlertExplanationDialog 关键交互回归。
 *
 * 范围:
 * - 打开时按 recordId 拉取解释
 * - 404(未生成)折叠为空态,不弹错误
 * - 正常数据渲染摘要 / 可能原因 / 排查建议
 *
 * 策略:mock @/api/ai,spy ElMessage,stub Element Plus 组件。
 */

vi.mock('@/api/ai', () => ({
  getAlertExplanation: vi.fn(),
  describeAiError: vi.fn(() => '解释加载失败')
}))

const getAlertExplanationMock = vi.mocked(getAlertExplanation)

const makeExplanation = (): AiAlertExplanation => ({
  record_id: 55,
  summary: '内存越界的根因疑似堆配置过高。',
  possible_causes: ['堆内存配置过大', '存在内存泄漏'],
  impact: ['可能触发 OOM Killer'],
  suggestions: ['排查 RES 前 10 进程'],
  limitations: ['仅为分析,不构成已执行的处置'],
  usage: { input_tokens: 10, output_tokens: 5, total_tokens: 15 },
  provider: 'openai-compatible',
  model: 'deepseek-chat',
  prompt_version: 'ai-alert-explanation-v1',
  created_at: '2026-09-09T10:20:00Z'
})

const globalStubs = {
  'el-dialog': { template: '<div class="el-dialog-stub"><slot /><slot name="footer" /></div>' },
  'el-alert': {
    props: { title: { type: String, default: '' } },
    template: '<div class="el-alert-stub">{{ title }}</div>'
  },
  'el-empty': {
    props: { description: { type: String, default: '' } },
    template: '<div class="el-empty-stub">{{ description }}<slot /></div>'
  },
  'el-result': {
    props: {
      icon: { type: String, default: '' },
      title: { type: String, default: '' },
      subTitle: { type: String, default: '' }
    },
    template:
      '<div class="el-result-stub"><span class="el-result-stub__title">{{ title }}</span>' +
      '<span class="el-result-stub__sub">{{ subTitle }}</span><slot name="extra" /></div>'
  },
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
  const wrapper = mount(AiAlertExplanationDialog, {
    props: { modelValue: true, recordId: 55 },
    global: {
      stubs: globalStubs,
      // v-loading 指令在 vitest 环境未注册,提供空实现避免告警。
      directives: { loading: {} }
    }
  })
  await flush()
  await flush()
  return wrapper
}

describe('AiAlertExplanationDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.spyOn(ElMessage, 'error').mockImplementation((() => ({})) as never)
  })

  it('打开时按 recordId 拉取解释', async () => {
    getAlertExplanationMock.mockResolvedValue({
      code: 0,
      message: 'success',
      data: makeExplanation()
    })
    await mountDialog()
    expect(getAlertExplanationMock).toHaveBeenCalledWith(55)
  })

  it('渲染摘要与分区内容', async () => {
    getAlertExplanationMock.mockResolvedValue({
      code: 0,
      message: 'success',
      data: makeExplanation()
    })
    const wrapper = await mountDialog()
    expect(wrapper.text()).toContain('内存越界的根因疑似堆配置过高。')
    expect(wrapper.text()).toContain('可能原因')
    expect(wrapper.text()).toContain('堆内存配置过大')
    expect(wrapper.text()).toContain('排查 RES 前 10 进程')
  })

  it('404 未生成时渲染空态且不弹错误', async () => {
    getAlertExplanationMock.mockResolvedValue({
      code: 40400,
      message: 'resource not found',
      data: null
    })
    const wrapper = await mountDialog()
    expect(wrapper.text()).toContain('该告警暂无 AI 解释')
    expect(ElMessage.error).not.toHaveBeenCalled()
  })

  it('其他错误渲染失败态(区别于空态)并经 describeAiError 映射后提示', async () => {
    getAlertExplanationMock.mockRejectedValue(new Error('network down'))
    const wrapper = await mountDialog()
    expect(ElMessage.error).toHaveBeenCalledWith('解释加载失败')
    expect(wrapper.find('.el-result-stub__title').text()).toBe('解释加载失败')
    expect(wrapper.text()).not.toContain('该告警暂无 AI 解释')
  })
})
