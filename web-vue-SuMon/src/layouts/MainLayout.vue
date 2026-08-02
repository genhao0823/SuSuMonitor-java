<template>
  <el-container class="main-layout">
    <el-aside
      :width="sidebarWidth"
      class="main-layout__sidebar"
    >
      <div class="main-layout__brand">
        SuSuMonitor
      </div>
      <el-menu
        :default-active="activeRoute"
        class="main-layout__menu"
        background-color="transparent"
        text-color="#cbd5e1"
        active-text-color="#ffffff"
        @select="handleMenuSelect"
      >
        <el-menu-item
          v-for="item in visibleMenus"
          :key="item.name"
          :index="item.name"
        >
          <el-icon>
            <component :is="item.icon" />
          </el-icon>
          <template #title>
            {{ item.label }}
          </template>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container class="main-layout__body">
      <el-header class="main-layout__header">
        <div class="main-layout__header-left">
          <el-tooltip
            :content="sidebarToggleLabel"
            placement="bottom"
          >
            <button
              type="button"
              class="main-layout__sidebar-toggle"
              :aria-label="sidebarToggleLabel"
              @click="toggleSidebar"
            >
              <el-icon>
                <component :is="isSidebarCollapsed ? Expand : Fold" />
              </el-icon>
            </button>
          </el-tooltip>
          <div class="main-layout__header-title">
            {{ pageTitle }}
          </div>
        </div>
        <div class="main-layout__user">
          <el-dropdown
            trigger="click"
            @command="handleCommand"
          >
            <span class="main-layout__user-trigger">
              <el-icon>
                <UserFilled />
              </el-icon>
              <span class="main-layout__username">
                {{ auth.user?.username ?? '未登录' }}
              </span>
              <el-tag
                v-if="auth.user"
                :type="auth.user.role === 'admin' ? 'danger' : 'info'"
                size="small"
                effect="dark"
                class="main-layout__role"
              >
                {{ userRoleLabel(auth.user.role) }}
              </el-tag>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="main-layout__main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Bell,
  DataLine,
  Document,
  Expand,
  Fold,
  Monitor,
  Notification,
  Promotion,
  UserFilled
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { userRoleLabel } from '@/utils/format'

interface MenuItem {
  name: string
  label: string
  icon: 'Monitor' | 'DataLine' | 'Document' | 'Bell' | 'Notification' | 'Promotion'
  requiresAdmin?: boolean
  /**
   * 菜单是否需要选择目标 server 才能跳转(Web 终端依赖 :serverId 形参)。
   * 为 true 时点击菜单弹 ElDialog 输入 serverId,空值则保留在当前页。
   */
  requiresServer?: boolean
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const isSidebarCollapsed = ref(true)

const sidebarWidth = computed(() => isSidebarCollapsed.value ? '0px' : '220px')
const sidebarToggleLabel = computed(() => isSidebarCollapsed.value ? '展开侧栏' : '收起侧栏')

function toggleSidebar(): void {
  isSidebarCollapsed.value = !isSidebarCollapsed.value
}

/**
 * 主菜单定义。`requiresAdmin` 控制菜单可见性,与路由守卫配合实现双向拦截。
 */
const allMenus: MenuItem[] = [
  { name: 'dashboard', label: '仪表盘', icon: 'Monitor' },
  { name: 'servers', label: '服务器', icon: 'DataLine' },
  { name: 'alert-records', label: '告警记录', icon: 'Bell' },
  { name: 'alert-rules', label: '告警规则', icon: 'Notification', requiresAdmin: true },
  { name: 'admin-users', label: '用户审核', icon: 'Document', requiresAdmin: true },
  { name: 'terminal', label: 'Web 终端', icon: 'Promotion', requiresServer: true }
]

const visibleMenus = computed<MenuItem[]>(() =>
  allMenus.filter((item) =>
    item.requiresAdmin === true ? auth.isAdmin : true
  )
)

const iconMap: Record<MenuItem['icon'], typeof Monitor> = {
  Monitor,
  DataLine,
  Document,
  Bell,
  Notification,
  Promotion
}

const pageTitle = computed<string>(() => {
  const menu = allMenus.find((m) => m.name === route.name)
  if (menu) {
    return menu.label
  }
  return (route.meta.title as string | undefined) ?? 'SuSuMonitor'
})

const activeRoute = computed<string>(() => {
  const name = route.name
  if (typeof name !== 'string') {
    return ''
  }
   if (name === 'servers' || name === 'server-detail' || name === 'server-metrics') {
    return 'servers'
  }
  if (name === 'admin-users') {
    return 'admin-users'
  }
  if (name === 'alert-records' || name === 'alert-rules') {
    return name
  }
  if (name === 'terminal') {
    return 'terminal'
  }
  return name
})

/**
 * 处理用户下拉菜单命令。当前仅支持退出登录,后续可扩展"个人资料"等。
 *
 * @param command dropdown 触发的命令
 */
async function handleCommand(command: string): Promise<void> {
  if (command === 'logout') {
    await auth.logout()
    ElMessage.success('已退出登录')
    await router.push({ name: 'login' })
  }
}

/**
 * 侧栏菜单点击处理。
 * - 普通菜单:直接跳到对应命名路由
 * - requiresServer 菜单(Web 终端):弹 prompt 输入 serverId,合法后跳 `/terminal/:serverId`,
 *   非法或取消则保留在当前页
 */
async function handleMenuSelect(name: string): Promise<void> {
  const menu = allMenus.find((m) => m.name === name)
  if (!menu) return
  if (!menu.requiresServer) {
    await router.push({ name: menu.name })
    return
  }
  try {
    const { value } = await ElMessageBox.prompt(
      '请输入目标服务器 ID(正整数)',
      '打开 Web 终端',
      {
        inputPattern: /^[1-9]\d*$/,
        inputErrorMessage: '请输入正整数 serverId',
        confirmButtonText: '打开',
        cancelButtonText: '取消'
      }
    )
    const serverId = Number.parseInt(value, 10)
    if (Number.isNaN(serverId) || serverId <= 0) return
    await router.push({ name: 'terminal', params: { serverId: String(serverId) } })
  } catch {
    // 用户取消 prompt:静默保留当前页
  }
}

defineExpose({ iconMap })
</script>

<style scoped>
.main-layout {
  height: 100vh;
}

.main-layout__sidebar {
  background: #1e293b;
  color: #f8fafc;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  transition: width 180ms ease;
}

.main-layout__brand {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 1px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.main-layout__menu {
  flex: 1;
  border-right: none;
}

.main-layout__body {
  background: var(--el-bg-color-page);
  transition: width 180ms ease;
}

.main-layout__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  background: var(--el-bg-color);
  border-bottom: 1px solid var(--el-border-color-lighter);
  height: 56px;
}

.main-layout__header-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.main-layout__sidebar-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  color: var(--el-text-color-regular);
  background: transparent;
  border: 0;
  border-radius: 6px;
  cursor: pointer;
}

.main-layout__sidebar-toggle:hover {
  color: var(--el-color-primary);
  background: var(--el-fill-color-light);
}

.main-layout__sidebar-toggle:focus-visible {
  outline: 2px solid var(--el-color-primary-light-5);
  outline-offset: 2px;
}

.main-layout__header-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.main-layout__user {
  display: flex;
  align-items: center;
}

.main-layout__user-trigger {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 6px 10px;
  border-radius: 6px;
  color: var(--el-text-color-regular);
}

.main-layout__user-trigger:hover {
  background: var(--el-fill-color-light);
}

.main-layout__username {
  font-size: 13px;
}

.main-layout__role {
  margin-left: 4px;
}

.main-layout__main {
  padding: 24px;
  overflow: auto;
}
</style>
