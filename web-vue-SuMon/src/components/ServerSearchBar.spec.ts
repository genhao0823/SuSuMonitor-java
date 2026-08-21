import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import ServerSearchBar from '@/components/ServerSearchBar.vue'

/**
 * ServerSearchBar 单元测试。
 *
 * 覆盖 keyword/pageSize 双向绑定和 reload 事件。
 */
const globalStubs = {
  'el-input': {
    props: ['modelValue'],
    emits: ['update:modelValue', 'keyup'],
    template:
      '<input class="el-input-stub" :value="modelValue ?? \'\'" @input="$emit(\'update:modelValue\', $event.target.value)" @keyup.enter="$emit(\'keyup\', $event)" />'
  },
  'el-select': {
    props: ['modelValue'],
    emits: ['update:modelValue', 'change'],
    template:
      '<select class="el-select-stub" :value="modelValue" @change="$emit(\'update:modelValue\', Number($event.target.value)); $emit(\'change\', Number($event.target.value))"><slot /></select>'
  },
  'el-option': { template: '<option class="el-option-stub"><slot /></option>' },
  'el-button': {
    emits: ['click'],
    template: '<button class="el-button-stub" @click="$emit(\'click\')"><slot /></button>'
  }
}

async function flush(): Promise<void> {
  await nextTick()
}

function mountSearchBar(keyword = '', pageSize = 10) {
  return mount(ServerSearchBar, {
    props: { keyword, pageSize, pageSizeOptions: [10, 20, 50] },
    global: { stubs: globalStubs }
  })
}

describe('ServerSearchBar', () => {
  it('keyword v-model 双向绑定', async () => {
    const wrapper = mountSearchBar()
    await wrapper.find('.el-input-stub').setValue('web')
    await flush()
    expect(wrapper.emitted('update:keyword')?.[0]).toEqual(['web'])
  })

  it('keyword 输入框按回车触发 reload', async () => {
    const wrapper = mountSearchBar()
    await wrapper.find('.el-input-stub').trigger('keyup.enter')
    await flush()
    expect(wrapper.emitted('reload')).toHaveLength(1)
  })

  it('pageSize 选择变化更新 model 并触发 reload', async () => {
    const wrapper = mountSearchBar()
    await wrapper.find('.el-select-stub').setValue('20')
    await flush()
    expect(wrapper.emitted('update:pageSize')?.[0]).toEqual([20])
    expect(wrapper.emitted('reload')).toHaveLength(1)
  })

  it('刷新按钮触发 reload', async () => {
    const wrapper = mountSearchBar()
    await wrapper.find('.el-button-stub').trigger('click')
    await flush()
    expect(wrapper.emitted('reload')).toHaveLength(1)
  })

  it('初始 keyword 和 pageSize 渲染到控件', () => {
    const wrapper = mountSearchBar('init-keyword', 20)
    expect((wrapper.find('.el-input-stub').element as HTMLInputElement).value).toBe('init-keyword')
    expect((wrapper.find('.el-select-stub').element as HTMLSelectElement).value).toBe('20')
  })
})
