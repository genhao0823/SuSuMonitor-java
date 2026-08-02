import { describe, it, expect, beforeEach, vi } from 'vitest'
import { nextTick, h, type SetupContext } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import AlertRuleDialog from '@/components/AlertRuleDialog.vue'
import type { AlertRule } from '@/types/api'

/**
 * AlertRuleDialog 逃逸窗口 confirm_count 回归。
 *
 * 范围:
 * - 编辑模式:传入 rule.confirm_count 回填表单;提交 updateRule 携带 confirm_count
 * - 新建模式:表单默认 confirm_count=1;提交 createRule 携带 confirm_count=1
 *
 * 策略:mock stores/alerts(createRule/updateRule),stub Element Plus 表单组件。
 */

// 共享单例:dialog 与测试必须持有同一份 mock,否则调用记录不互通。
const storeMock = {
  createRule: vi.fn(),
  updateRule: vi.fn()
}

vi.mock('@/stores/alerts', () => ({
  useAlertsStore: () => storeMock
}))

const makeRule = (overrides: Partial<AlertRule> = {}): AlertRule => ({
  id: 1,
  server_id: null,
  metric: 'cpu',
  operator: '>',
  threshold_value: 80,
  level: 'warning',
  confirm_count: 1,
  enabled: true,
  created_by: 1,
  created_at: '2026-07-22T00:00:00Z',
  updated_at: '2026-07-22T00:00:00Z',
  ...overrides
})

const globalStubs = {
  'el-dialog': { template: '<div class="el-dialog-stub"><slot name="footer" /><slot /></div>' },
  'el-form': {
    props: ['model'],
    // 暴露 validate 供 handleSubmit 调用(对话框提交前表单校验依赖它)。
    setup(_props: unknown, { expose, slots }: SetupContext) {
      expose({ validate: () => Promise.resolve(true) })
      return () =>
        h('form', { class: 'el-form-stub' }, [
          h('div', { class: 'el-form-stub__model' }, JSON.stringify((_props as { model?: unknown })?.model ?? {})),
          slots.default?.()
        ])
    }
  },
  'el-form-item': { template: '<div class="el-form-item-stub"><slot /></div>' },
  'el-input-number': {
    props: ['modelValue', 'min'],
    emits: ['update:modelValue'],
    template:
      '<input class="el-input-number-stub" :value="modelValue" :min="min" ' +
      '@input="$emit(\'update:modelValue\', Number($event.target.value))" />'
  },
  'el-select': { template: '<div class="el-select-stub"><slot /></div>' },
  'el-option': { template: '<div class="el-option-stub" />' },
  'el-switch': { template: '<div class="el-switch-stub" />' },
  'el-button': {
    emits: ['click'],
    template: '<button class="el-button-stub" @click="$emit(\'click\')"><slot /></button>'
  },
  'el-row': { template: '<div class="el-row-stub"><slot /></div>' },
  'el-col': { template: '<div class="el-col-stub"><slot /></div>' }
}

async function flush(): Promise<void> {
  await nextTick()
  await Promise.resolve()
}

describe('AlertRuleDialog 逃逸窗口 confirm_count', () => {
  let wrapper: VueWrapper
  const createRule = storeMock.createRule
  const updateRule = storeMock.updateRule

  const mountDialog = async (rule: AlertRule | null): Promise<void> => {
    wrapper = mount(AlertRuleDialog, {
      props: { modelValue: true, rule, serverOptions: [] },
      global: { stubs: globalStubs }
    })
    await flush()
  }

  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('编辑模式:rule.confirm_count 回填表单,提交 updateRule 携带 confirm_count', async () => {
    await mountDialog(makeRule({ confirm_count: 5 }))

    // 表单中阈值(0)与确认次数(1)两个 input-number,取第二个。
    const confirmInput = wrapper.findAll('.el-input-number-stub')[1]
    expect(confirmInput.attributes('value')).toBe('5')

    const submitButton = wrapper.findAll('.el-button-stub').find((b) => b.text().trim() === '保存')
    expect(submitButton).toBeDefined()
    await submitButton?.trigger('click')
    await flush()

    expect(updateRule).toHaveBeenCalledWith(1, expect.objectContaining({ confirm_count: 5 }))
    expect(createRule).not.toHaveBeenCalled()
  })

  it('新建模式:表单默认 confirm_count=1,提交 createRule 携带 confirm_count=1', async () => {
    await mountDialog(null)

    const confirmInput = wrapper.findAll('.el-input-number-stub')[1]
    expect(confirmInput.attributes('value')).toBe('1')

    const submitButton = wrapper.findAll('.el-button-stub').find((b) => b.text().trim() === '创建')
    expect(submitButton).toBeDefined()
    await submitButton?.trigger('click')
    await flush()

    expect(createRule).toHaveBeenCalledWith(expect.objectContaining({ confirm_count: 1 }))
    expect(updateRule).not.toHaveBeenCalled()
  })
})