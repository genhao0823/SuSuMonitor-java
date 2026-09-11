<template>
  <el-card
    class="dashboard-servers-card liquid-glass-card"
    shadow="never"
  >
    <template #header>
      <div class="dashboard-servers-card__header">
        <div class="dashboard-servers-card__title">
          <TushanFoxMark
            :size="32"
            alt="涂山苏苏·服务器"
          />
          <span>服务器总数</span>
        </div>
        <span class="liquid-badge liquid-badge--info">{{ count }} 台</span>
      </div>
    </template>
    <el-skeleton
      v-if="loading"
      :rows="5"
      animated
    />
    <template v-else>
      <div class="dashboard-servers-card__value-wrap">
        <div class="dashboard-servers-card__value">
          {{ count }}
        </div>
        <div
          class="dashboard-servers-card__status"
          aria-label="服务器状态分布"
        >
          <span class="dashboard-servers-card__state dashboard-servers-card__state--online">
            <span class="liquid-dot liquid-dot--ok" /> 在线 {{ online }}
          </span>
          <span class="dashboard-servers-card__state dashboard-servers-card__state--offline">
            <span class="liquid-dot liquid-dot--down" /> 离线 {{ offline }}
          </span>
          <span class="dashboard-servers-card__state dashboard-servers-card__state--unknown">
            <span class="liquid-dot liquid-dot--warn" /> 未知 {{ unknown }}
          </span>
        </div>
      </div>
      <p class="dashboard-servers-card__scope">
        {{ complete ? '已统计全部服务器资产' : `当前已统计前 ${sampledCount} 台服务器` }}
      </p>
      <div class="dashboard-servers-card__example">
        <p class="dashboard-servers-card__example-title">
          <span class="liquid-dot liquid-dot--pink" />
          示例服务器{{ serverName ? `：${serverName}` : '' }}
        </p>
        <div class="dashboard-servers-card__spark-wrap">
          <ServerSparkLine
            :data="data"
            :label="serverName ? `${serverName} · CPU 7d` : '示例服务器 · CPU 7d'"
          />
        </div>
        <div class="dashboard-servers-card__metrics">
          <p class="dashboard-servers-card__metrics-info">
            {{ trendSamples > 0 ? `${trendSamples} 个历史采样点` : '暂无历史采样' }}
            <span v-if="trendCollectedAt">（最近采集于 {{ formatDateTime(trendCollectedAt) }}）</span>
          </p>
          <template v-if="latestCollectedAt">
            <p class="dashboard-servers-card__latest-title">
              最近资源采集
            </p>
            <div class="dashboard-servers-card__latest-values">
              <span>CPU <strong>{{ percentage(latestCpu) }}</strong></span>
              <span>内存 <strong>{{ percentage(latestMemory) }}</strong></span>
              <span>磁盘 <strong>{{ percentage(latestDisk) }}</strong></span>
            </div>
            <p class="dashboard-servers-card__time">
              {{ formatDateTime(latestCollectedAt) }}
            </p>
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
.dashboard-servers-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.dashboard-servers-card__title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 15px;
  font-weight: 700;
  color: var(--susu-ink);
  letter-spacing: 0;
}

.dashboard-servers-card__value-wrap {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}

.dashboard-servers-card__value {
  color: #b7325c;
  font-size: 38px;
  font-weight: 800;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
}

.dashboard-servers-card__status {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.dashboard-servers-card__state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 700;
  border: 1px solid var(--susu-border-glass);
  backdrop-filter: blur(6px);
}

.dashboard-servers-card__state--online {
  color: #15803d;
  background: rgba(34, 197, 94, 0.12);
}

.dashboard-servers-card__state--offline {
  color: #be123c;
  background: rgba(244, 63, 94, 0.12);
}

.dashboard-servers-card__state--unknown {
  color: #b45309;
  background: rgba(245, 158, 11, 0.14);
}

.dashboard-servers-card__scope {
  margin: 10px 0 0;
  color: #8a5872;
  font-size: 12px;
}

.dashboard-servers-card__example {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid rgba(39, 39, 42, 0.08);
}

.dashboard-servers-card__example-title {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  color: #6d3b54;
  font-size: 12px;
  font-weight: 700;
}

.dashboard-servers-card__spark-wrap {
  margin-top: 8px;
  padding: 8px 0 4px;
}

.dashboard-servers-card__metrics {
  margin-top: 10px;
}

.dashboard-servers-card__metrics-info {
  margin: 0;
  color: #8a5872;
  font-size: 11.5px;
}

.dashboard-servers-card__latest-title {
  margin: 10px 0 6px !important;
  color: #6d3b54 !important;
  font-weight: 700;
  font-size: 11.5px;
}

.dashboard-servers-card__latest-values {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-top: 6px;
}

.dashboard-servers-card__latest-values span {
  padding: 8px 6px;
  color: #6d3b54;
  font-size: 11.5px;
  text-align: center;
  white-space: nowrap;
}

.dashboard-servers-card__latest-values span + span {
  border-left: 1px solid rgba(39, 39, 42, 0.08);
}

.dashboard-servers-card__latest-values span strong {
  color: var(--susu-ink);
  font-weight: 700;
}

.dashboard-servers-card__time {
  margin: 6px 0 0;
  color: #9b7c8e;
  font-size: 11px;
}

.dashboard-servers-card__latest-empty {
  margin: 8px 0 0;
  color: #9b7c8e !important;
  font-size: 11.5px;
}
</style>
