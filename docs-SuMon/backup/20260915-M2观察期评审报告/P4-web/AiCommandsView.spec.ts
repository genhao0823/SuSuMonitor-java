import { describe, it, expect, beforeEach, vi } from 'vitest'
import { computed, h, inject, nextTick, provide, type ComputedRef, type PropType, type SetupContext } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import { ElMessage, ElMessageBox } from 'element-plus'
import AiCommandsView from '@/views/AiCommandsView.vue'
import {
  approveCommandRun,
  createManualCommandRun,
  getAutoApprovalPolicy,
  listCommandRuns,
  listCommandTemplates,
  updateAutoApprovalPolicy
} from '@/api/command'
import type { AutoApprovalPolicy, CommandRun, CommandTemplate, PageResult } from '@/types/api'

/**
 * AiCommandsView 关键交互回归。
 *
 * 范围:
 * - 运行记录:待审批行渲染「通过 / 拒绝」;审批走 ElMessageBox 二次确认后调用接口并刷新列表
 * - 手动创建:选择服务器与模板后提交,仅携带 server_id + template_id(无参数时不带 params 字段)
 * - 自动审批:策略 Tab 回显快照;保存调用 PUT;自动审批行渲染「自动」徽标
 *
 * 策略:mock @/api/command、@/api/server、@/api/ai;ElMessageBox.confirm spy;
 * el-table/el-table-column 通过 provide/inject 让列的 scoped slot 拿到 row。
 */

vi.mock('@/api/command', () => ({
  approveCommandRun: vi.fn(),
  createManualCommandRun: vi.fn(),
  getAutoApprovalPolicy: vi.fn(),
  getCommandRun: vi.fn(),
  listCommandRuns: vi.fn(),
  listCommandTemplates: vi.fn(),
  rejectCommandRun: vi.fn(),
  requestCommandSuggestions: vi.fn(),
  updateAutoApprovalPolicy: vi.fn()
}))

vi.mock('@/api/server', () => ({
  listServers: vi.fn(async () => ({
    code: 0,
    message: 'success',
    data: { items: [], total: 0, page: 1, page_size: 20 }
  }))
}))

vi.mock('@/api/ai', () => ({
  describeAiError: vi.fn(() => '映射后的错误文案')
}))

const listCommandRunsMock = vi.mocked(listCommandRuns)
const approveCommandRunMock = vi.mocked(approveCommandRun)
const createManualCommandRunMock = vi.mocked(createManualCommandRun)
const listCommandTemplatesMock = vi.mocked(listCommandTemplates)
const getAutoApprovalPolicyMock = vi.mocked(getAutoApprovalPolicy)
const updateAutoApprovalPolicyMock = vi.mocked(updateAutoApprovalPolicy)

const makePolicy = (overrides: Partial<AutoApprovalPolicy> = {}): AutoApprovalPolicy => ({
  enabled: false,
  max_risk_level: 'medium',
  updated_at: null,
  updated_by: null,
  ...overrides
})

const makeRun = (overrides: Partial<CommandRun> = {}): CommandRun => ({
  id: 101,
  execution_id: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
  server_id: 1,
  template_id: 'top_mem_processes',
  rendered_command: 'ps aux --sort=-%mem | head -n 6',
  status: 'pending_approval',
  source: 'ai',
  proposal: null,
  result: null,
  exit_code: null,
  proposer_id: 1,
  approver_id: null,
  expires_at: '2026-09-09T10:45:00Z',
  created_at: '2026-09-09T10:15:00Z',
  completed_at: null,
  ...overrides
})

const makePage = (items: CommandRun[]): PageResult<CommandRun> => ({
  items,
  total: items.length,
  page: 1,
  page_size: 20
})

/* ------------------------- Element Plus 通用 stub ------------------------- */

/** el-table 通过 provide 把行数据交给 el-table-column 的 scoped slot。 */
const TABLE_DATA_KEY = 'stub-table-data'

// 注意:此处按项目既有 spec 约定使用"普通对象"形式定义 stub 组件,
// 避免触发 vue/one-component-per-file(该规则只识别 defineComponent 调用)。

const ElTableStub = {
  name: 'ElTableStub',
  props: { data: { type: Array as PropType<unknown[]>, default: () => [] } },
  setup(props: Record<string, unknown>, { slots }: SetupContext) {
    provide(TABLE_DATA_KEY, computed(() => (props.data ?? []) as unknown[]))
    return () => h('div', { class: 'el-table-stub' }, slots.default?.())
  }
}

const ElTableColumnStub = {
  name: 'ElTableColumnStub',
  props: {
    prop: { type: String, default: undefined },
    label: { type: String, default: '' }
  },
  setup(props: Record<string, unknown>, { slots }: SetupContext) {
    const rows = inject<ComputedRef<unknown[]>>(TABLE_DATA_KEY, computed(() => []))
    const colKey = typeof props.prop === 'string' ? props.prop : String(props.label ?? '')
    return () => {
      if (slots.default === undefined) return null
      return h(
        'div',
        { class: 'el-table-column-stub', 'data-col': colKey },
        rows.value.map((row, index) => slots.default?.({ row, index }))
      )
    }
  }
}

const ElSelectStub = {
  name: 'ElSelectStub',
  props: {
    modelValue: { type: [Number, String] as PropType<number | string | null>, default: null },
    placeholder: { type: String, default: '' }
  },
  emits: ['update:modelValue', 'change'],
  template: '<div class="el-select-stub" :data-placeholder="placeholder"><slot /></div>'
}

const ElInputStub = {
  name: 'ElInputStub',
  props: { modelValue: { type: String, default: '' } },
  emits: ['update:modelValue'],
  template:
    '<textarea class="el-input-stub" :value="modelValue" ' +
    '@input="$emit(\'update:modelValue\', $event.target.value)" />'
}

const ElRadioGroupStub = {
  name: 'ElRadioGroupStub',
  props: { modelValue: { type: String, default: '' } },
  emits: ['update:modelValue'],
  template: '<div class="el-radio-group-stub"><slot /></div>'
}

const ElFormStub = {
  name: 'ElFormStub',
  props: { model: { type: Object, default: undefined } },
  setup(_props: unknown, { expose, slots }: SetupContext) {
    expose({ validate: () => Promise.resolve(true) })
    return () => h('form', { class: 'el-form-stub' }, slots.default?.())
  }
}

const globalStubs = {
  'el-card': { template: '<div class="el-card-stub"><slot /></div>' },
  'el-tabs': { template: '<div class="el-tabs-stub"><slot /></div>' },
  'el-tab-pane': { template: '<div class="el-tab-pane-stub"><slot /></div>' },
  'el-radio-group': ElRadioGroupStub,
  'el-radio-button': { template: '<div class="el-radio-button-stub"><slot /></div>' },
  'el-select': ElSelectStub,
  'el-option': { template: '<div class="el-option-stub" />' },
  'el-table': ElTableStub,
  'el-table-column': ElTableColumnStub,
  'el-pagination': { template: '<div class="el-pagination-stub" />' },
  'el-dialog': { template: '<div class="el-dialog-stub"><slot /><slot name="footer" /></div>' },
  'el-descriptions': { template: '<div class="el-descriptions-stub"><slot /></div>' },
  'el-descriptions-item': { template: '<div class="el-descriptions-item-stub"><slot /></div>' },
  'el-alert': {
    props: { title: { type: String, default: '' } },
    template: '<div class="el-alert-stub">{{ title }}</div>'
  },
  'el-empty': {
    props: { description: { type: String, default: '' } },
    template: '<div class="el-empty-stub">{{ description }}</div>'
  },
  'el-tag': { template: '<span class="el-tag-stub"><slot /></span>' },
  'el-switch': {
    props: { modelValue: { type: Boolean, default: false } },
    emits: ['update:modelValue'],
    template:
      '<div class="el-switch-stub" :data-checked="modelValue" @click="$emit(\'update:modelValue\', !modelValue)" />'
  },
  'el-form': ElFormStub,
  'el-form-item': { template: '<div class="el-form-item-stub"><slot /></div>' },
  'el-input': ElInputStub,
  'el-button': {
    emits: ['click'],
    template: '<button class="el-button-stub" @click="$emit(\'click\')"><slot /></button>'
  }
}

async function flush(): Promise<void> {
  // onMounted 依次 await 服务器选项 / 运行列表 / 模板三段异步,
  // 单轮微任务推进不足,循环多轮确保挂载后的链式加载全部落地。
  for (let i = 0; i < 6; i += 1) {
    await Promise.resolve()
    await nextTick()
  }
}

async function mountView(): Promise<VueWrapper> {
  const wrapper = mount(AiCommandsView, {
    global: {
      stubs: globalStubs,
      directives: { loading: {} }
    }
  })
  await flush()
  await flush()
  return wrapper
}

function findButton(wrapper: VueWrapper, text: string): ReturnType<VueWrapper['find']> | undefined {
  return wrapper.findAll('.el-button-stub').find((b) => b.text().trim() === text)
}

describe('AiCommandsView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.spyOn(ElMessage, 'error').mockImplementation((() => ({})) as never)
    vi.spyOn(ElMessage, 'warning').mockImplementation((() => ({})) as never)
    vi.spyOn(ElMessage, 'success').mockImplementation((() => ({})) as never)
    listCommandRunsMock.mockResolvedValue({
      code: 0,
      message: 'success',
      data: makePage([makeRun()])
    })
    listCommandTemplatesMock.mockResolvedValue({
      code: 0,
      message: 'success',
      data: [
        {
          id: 'top_mem_processes',
          argv: ['ps', 'aux'],
          params: [{ name: 'count', pattern: '^[1-9][0-9]?$' }]
        }
      ] satisfies CommandTemplate[]
    })
    getAutoApprovalPolicyMock.mockResolvedValue({
      code: 0,
      message: 'success',
      data: makePolicy()
    })
  })

  it('待审批行渲染通过/拒绝按钮,审批经二次确认后调用接口并刷新列表', async () => {
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    approveCommandRunMock.mockResolvedValue({
      code: 0,
      message: 'success',
      data: makeRun({ status: 'approved' })
    })

    const wrapper = await mountView()
    const approveButton = findButton(wrapper, '通过')
    expect(approveButton).toBeDefined()
    const rejectButton = findButton(wrapper, '拒绝')
    expect(rejectButton).toBeDefined()

    await approveButton?.trigger('click')
    await flush()

    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(approveCommandRunMock).toHaveBeenCalledWith(101)
    // 初次加载 + 审批后刷新,共两次
    expect(listCommandRunsMock).toHaveBeenCalledTimes(2)
  })

  it('取消二次确认时不调用审批接口', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel' as never)
    const wrapper = await mountView()
    await findButton(wrapper, '通过')?.trigger('click')
    await flush()
    expect(approveCommandRunMock).not.toHaveBeenCalled()
  })

  it('手动创建:选择服务器与模板后提交,无参数时不携带 params 字段', async () => {
    createManualCommandRunMock.mockResolvedValue({
      code: 0,
      message: 'success',
      data: makeRun({ id: 102, source: 'manual', status: 'pending_approval' })
    })

    const wrapper = await mountView()

    // 切到「创建命令」Tab:从 radio-group stub 发出 v-model 更新
    const radioGroup = wrapper.findComponent({ name: 'ElRadioGroupStub' })
    radioGroup.vm.$emit('update:modelValue', 'manual')
    await flush()

    // 手动表单中的两个下拉:目标服务器 + 命令模板
    const selects = wrapper.findAllComponents({ name: 'ElSelectStub' })
    const manualServerSelect = selects.find((s) => s.attributes('data-placeholder') === '选择服务器')
    const templateSelect = selects.find((s) => s.attributes('data-placeholder') === '选择白名单模板')
    expect(manualServerSelect).toBeDefined()
    expect(templateSelect).toBeDefined()
    manualServerSelect?.vm.$emit('update:modelValue', 3)
    templateSelect?.vm.$emit('update:modelValue', 'top_mem_processes')
    await flush()

    const createButton = findButton(wrapper, '创建待审批命令')
    await createButton?.trigger('click')
    await flush()

    expect(createManualCommandRunMock).toHaveBeenCalledTimes(1)
    expect(createManualCommandRunMock).toHaveBeenCalledWith({
      server_id: 3,
      template_id: 'top_mem_processes'
    })
  })

  it('自动审批策略 Tab:挂载回显禁用快照,开启后保存调用 PUT 并提示成功', async () => {
    getAutoApprovalPolicyMock.mockResolvedValue({
      code: 0,
      message: 'success',
      data: makePolicy({ enabled: false, max_risk_level: 'low' })
    })
    updateAutoApprovalPolicyMock.mockResolvedValue({
      code: 0,
      message: 'success',
      data: makePolicy({ enabled: true, max_risk_level: 'low' })
    })

    const wrapper = await mountView()

    // 挂载即回显策略快照(开关关闭 + 阈值 low)。
    expect(getAutoApprovalPolicyMock).toHaveBeenCalledTimes(1)
    const switchStub = wrapper.find('.el-switch-stub')
    expect(switchStub.attributes('data-checked')).toBe('false')

    // 开启开关后保存。
    await switchStub.trigger('click')
    await flush()
    await findButton(wrapper, '保存策略')?.trigger('click')
    await flush()

    expect(updateAutoApprovalPolicyMock).toHaveBeenCalledWith({
      enabled: true,
      max_risk_level: 'low'
    })
    expect(ElMessage.success).toHaveBeenCalled()
  })

  it('自动审批的运行行渲染「自动」徽标与风险标签', async () => {
    listCommandRunsMock.mockResolvedValue({
      code: 0,
      message: 'success',
      data: makePage([
        makeRun({ status: 'executing', approval_mode: 'auto', risk_level: 'low' })
      ])
    })

    const wrapper = await mountView()

    const tagTexts = wrapper.findAll('.el-tag-stub').map((tag) => tag.text().trim())
    expect(tagTexts).toContain('自动')
    expect(tagTexts).toContain('低')
  })
})
