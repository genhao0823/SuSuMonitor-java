<template>
  <el-card
    class="dashboard-view__card dashboard-view__card--glass"
    shadow="never"
  >
    <template #header>
      <div class="dashboard-view__card-header">
        <div class="dashboard-view__card-title">
          <TushanFoxMark
            :size="32"
            alt="涂山苏苏·服务器"
          />
          服务器总数
        </div>
        <span class="dashboard-view__badge dashboard-view__badge--info">{{ count }}</span>
      </div>
    </template>
    <el-skeleton
      v-if="loading"
      :rows="5"
      animated
    />
    <template v-else>
      <div class="dashboard-view__card-value dashboard-view__card-value--accent">
        {{ count }}
      </div>
      <div
        class="dashboard-servers-card__status"
        aria-label="服务器状态分布"
      >
        <span class="dashboard-servers-card__state dashboard-servers-card__state--online">在线 {{ online }}</span>
        <span class="dashboard-servers-card__state dashboard-servers-card__state--offline">离线 {{ offline }}</span>
        <span class="dashboard-servers-card__state dashboard-servers-card__state--unknown">未知 {{ unknown }}</span>
      </div>
      <p class="dashboard-servers-card__scope">
        {{ complete ? '已统计全部服务器' : `当前已统计前 ${sampledCount} 台服务器` }}
      </p>
      <div class="dashboard-servers-card__example">
        <p class="dashboard-servers-card__example-title">
          示例服务器{{ serverName ? `：${serverName}` : '' }}
        </p>
        <div class="dashboard-view__spark-wrap">
          <ServerSparkLine
            :data="data"
            :label="serverName ? `${serverName} · CPU 7d` : '示例服务器 · CPU 7d'"
          />
        </div>
        <div class="dashboard-servers-card__metrics">
          <p>{{ trendSamples > 0 ? `${trendSamples} 个历史采样点` : '暂无历史采样' }}</p>
          <p v-if="trendCollectedAt">
            最近历史采集于 {{ formatDateTime(trendCollectedAt) }}
          </p>
          <template v-if="latestCollectedAt">
            <p class="dashboard-servers-card__latest-title">
              最近资源采集
            </p>
            <div class="dashboard-servers-card__latest-values">
              <span>CPU {{ percentage(latestCpu) }}</span>
              <span>内存 {{ percentage(latestMemory) }}</span>
              <span>磁盘 {{ percentage(latestDisk) }}</span>
            </div>
            <p>{{ formatDateTime(latestCollectedAt) }}</p>
          </template>
          <p
            v-else
            class="dashboard-servers-card__latest-empty"
          >
            暂无最近采集
          </p>
        </div>
      </div>
    </template>
  </el-card>
</template>

<script setup lang="ts">
import TushanFoxMark from '@/components/TushanFoxMark.vue'
import ServerSparkLine from '@/components/ServerSparkLine.vue'
import { formatDateTime } from '@/utils/format'

withDefaults(defineProps<{
  count: number
  data: number[]
  loading: boolean
  online?: number
  offline?: number
  unknown?: number
  sampledCount?: number
  complete?: boolean
  serverName?: string
  trendSamples?: number
  trendCollectedAt?: string | null
  latestCpu?: number | null
  latestMemory?: number | null
  latestDisk?: number | null
  latestCollectedAt?: string | null
}>(), {
  online: 0,
  offline: 0,
  unknown: 0,
  sampledCount: 0,
  complete: true,
  serverName: '',
  trendSamples: 0,
  trendCollectedAt: null,
  latestCpu: null,
  latestMemory: null,
  latestDisk: null,
  latestCollectedAt: null
})

function percentage(value: number | null): string {
  return value === null ? '-' : `${value.toFixed(1)}%`
}
</script>

<style scoped>
.dashboard-servers-card__status { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.dashboard-servers-card__state { padding: 3px 7px; border-radius: 7px; font-size: 11px; font-weight: 700; }
.dashboard-servers-card__state--online { color: #19754a; background: rgba(46, 184, 114, 0.14); }
.dashboard-servers-card__state--offline { color: #6d6370; background: rgba(109, 99, 112, 0.12); }
.dashboard-servers-card__state--unknown { color: #8a5410; background: rgba(231, 162, 58, 0.16); }
.dashboard-servers-card__scope, .dashboard-servers-card__metrics p { margin: 8px 0 0; color: #8a5872; font-size: 11px; line-height: 1.5; }
.dashboard-servers-card__example { margin-top: 14px; padding-top: 10px; border-top: 1px dashed rgba(183, 50, 92, 0.14); }
.dashboard-servers-card__example-title { margin: 0; color: #6d3b54; font-size: 12px; font-weight: 700; }
.dashboard-servers-card__metrics { margin-top: 10px; }
.dashboard-servers-card__latest-title { color: #6d3b54 !important; font-weight: 700; }
.dashboard-servers-card__latest-values { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 6px; margin-top: 8px; }
.dashboard-servers-card__latest-values span { padding: 6px 4px; color: #6d3b54; background: rgba(255, 255, 255, 0.42); border-radius: 7px; font-size: 11px; text-align: center; white-space: nowrap; }
.dashboard-servers-card__latest-empty { color: #9b7c8e !important; }
</style>
