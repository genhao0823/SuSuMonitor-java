<template>
  <div class="server-search-bar">
    <el-input
      v-model="keyword"
      placeholder="按名称或主机地址搜索"
      clearable
      class="server-search-bar__input"
      @keyup.enter="emit('reload')"
    >
      <template #prefix>
        <svg
          viewBox="0 0 24 24"
          xmlns="http://www.w3.org/2000/svg"
          aria-hidden="true"
        >
          <circle
            cx="11"
            cy="11"
            r="6"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
          />
          <path
            d="M16 16 L20 20"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            stroke-linecap="round"
          />
        </svg>
      </template>
    </el-input>
    <el-select
      v-model="pageSize"
      class="server-search-bar__page-size"
      @change="emit('reload')"
    >
      <el-option
        v-for="opt in pageSizeOptions"
        :key="opt"
        :label="`${opt} 条/页`"
        :value="opt"
      />
    </el-select>
    <el-button @click="emit('reload')">
      刷新
    </el-button>
  </div>
</template>

<script setup lang="ts">
/**
 * ServerListView 工具条:关键字搜索 + 每页大小 + 刷新。
 *
 * 通过 `defineModel` 暴露两个 v-model 字段,父组件可双向绑定;
 * `reload` 事件保留为手动强制刷新与回车触发统一入口。
 *
 * @model keyword 名称或主机地址搜索关键字
 * @model pageSize 每页大小(必填)
 * @prop pageSizeOptions 每页大小选项(必填)
 * @event reload 点击刷新、回车或切换每页大小时触发
 */
const keyword = defineModel<string>('keyword', { default: '' })
const pageSize = defineModel<number>('pageSize', { required: true })

defineProps<{
  pageSizeOptions: number[]
}>()

const emit = defineEmits<{
  (e: 'reload'): void
}>()
</script>

<style scoped>
.server-search-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.server-search-bar__input {
  flex: 1;
  min-width: 220px;
  max-width: 420px;
}

.server-search-bar__input :deep(.el-input__wrapper) {
  background: rgba(255, 255, 255, 0.85);
}

.server-search-bar__input :deep(.el-input__prefix svg) {
  width: 14px;
  height: 14px;
}

.server-search-bar__page-size {
  width: 130px;
}
</style>
