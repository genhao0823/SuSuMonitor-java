<template>
  <el-container class="main-layout">
    <el-aside
      :width="sidebarWidth"
      class="main-layout__sidebar"
    >
      <div class="main-layout__brand">
        <span class="main-layout__brand-su">Su</span><span class="main-layout__brand-su main-layout__brand-su--gold">Su</span><span class="main-layout__brand-mon">Monitor</span>
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
          class="main-layout__menu-item"
        >
          <el-icon class="main-layout__menu-icon">
            <component :is="item.icon" />
          </el-icon>
          <template #title>
            <span class="main-layout__menu-text">{{ item.label }}</span>
          </template>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container class="main-layout__body">
      <el-header class="main-layout__header liquid-glass-header">
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
        <div class="main-layout__header-right">
          <el-tooltip
            :content="themeToggleLabel"
            placement="bottom"
          >
            <button
              type="button"
              class="main-layout__sidebar-toggle"
              :aria-label="themeToggleLabel"
              @click="theme.toggle()"
            >
              <el-icon>
                <Moon v-if="!theme.isDark" />
                <Sunny v-else />
              </el-icon>
            </button>
          </el-tooltip>
          <el-dropdown
            trigger="click"
            @command="handleCommand"
          >
            <span class="main-layout__user-trigger">
              <span class="main-layout__avatar-wrap">
                <el-icon><UserFilled /></el-icon>
              </span>
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
  ChatDotRound,
  DataLine,
  Document,
  Expand,
  Fold,
  MagicStick,
  Monitor,
  Moon,
  Notification,
  Promotion,
  Setting,
  Sunny,
  UserFilled
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { userRoleLabel } from '@/utils/format'

interface MenuItem {
  name: string
  label: string
  icon:
    | 'Monitor'
    | 'DataLine'
    | 'Document'
    | 'Bell'
    | 'Notification'
    | 'Promotion'
    | 'ChatDotRound'
    | 'MagicStick'
    | 'Setting'
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
const theme = useThemeStore()
const isSidebarCollapsed = ref(true)

const sidebarWidth = computed(() => (isSidebarCollapsed.value ? '0px' : '230px'))
const sidebarToggleLabel = computed(() => (isSidebarCollapsed.value ? '展开侧栏' : '收起侧栏'))
const themeToggleLabel = computed(() => (theme.isDark ? '切换到浅色模式' : '切换到深色模式'))

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
  { name: 'ai-qa', label: 'AI 问答', icon: 'ChatDotRound', requiresAdmin: true },
  { name: 'ai-commands', label: 'AI 命令域', icon: 'MagicStick', requiresAdmin: true },
  { name: 'ai-settings', label: 'AI 设置', icon: 'Setting', requiresAdmin: true },
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
  Promotion,
  ChatDotRound,
  MagicStick,
  Setting
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
  position: relative;
  height: 100vh;
  background: var(--susu-page-bg);
}

.main-layout__sidebar {
  background: rgba(22, 28, 42, 0.88);
  backdrop-filter: blur(28px) saturate(190%);
  -webkit-backdrop-filter: blur(28px) saturate(190%);
  border-right: 1px solid rgba(255, 255, 255, 0.1);
  color: #f8fafc;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  transition: width 240ms cubic-bezier(0.4, 0, 0.2, 1);
  z-index: 10;
  box-shadow: 4px 0 24px rgba(0, 0, 0, 0.15);
}

.main-layout__brand {
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 19px;
  font-weight: 800;
  letter-spacing: 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(255, 255, 255, 0.03);
}

.main-layout__brand-su {
  color: #ff5b8a;
  text-shadow: 0 0 12px rgba(255, 91, 138, 0.6);
}

.main-layout__brand-su--gold {
  color: #f5b942;
  text-shadow: 0 0 12px rgba(245, 185, 66, 0.6);
}

.main-layout__brand-mon {
  color: #f1f5f9;
  font-size: 15px;
  margin-left: 4px;
  font-weight: 600;
  letter-spacing: 0;
}

.main-layout__menu {
  flex: 1;
  border-right: none;
  padding: 12px 10px;
}

.main-layout__menu-item {
  border-radius: 6px;
  margin-bottom: 6px;
  height: 46px;
  line-height: 46px;
  transition: all 0.22s ease;
  font-weight: 500;
}

.main-layout__menu-item:hover {
  background: rgba(255, 255, 255, 0.08) !important;
  color: #ffffff !important;
  transform: translateX(3px);
}

.main-layout__menu-item.is-active {
  background: #a82c51 !important;
  box-shadow: 0 6px 18px rgba(255, 91, 138, 0.4), inset 0 1px 0 rgba(255, 255, 255, 0.3) !important;
  color: #ffffff !important;
  font-weight: 700;
}

.main-layout__menu-icon {
  font-size: 17px;
  margin-right: 8px;
}

.main-layout__body {
  background: transparent;
  transition: width 240ms cubic-bezier(0.4, 0, 0.2, 1);
  position: relative;
  z-index: 1;
}

.main-layout__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  height: 60px;
  z-index: 5;
}

.main-layout__header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.main-layout__sidebar-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  padding: 0;
  color: var(--susu-ink-muted);
  background: var(--susu-surface);
  backdrop-filter: blur(8px);
  border: 1px solid var(--susu-border-glass);
  border-radius: 8px;
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(183, 50, 92, 0.06), inset 0 1px 0 var(--susu-border-glass);
  transition: all 0.22s ease;
}

.main-layout__sidebar-toggle:hover {
  color: #ff5b8a;
  background: var(--susu-surface-strong);
  transform: translateY(-1px);
  box-shadow: 0 4px 14px rgba(255, 91, 138, 0.2);
}

.main-layout__sidebar-toggle:focus-visible {
  outline: 2px solid #ff5b8a;
  outline-offset: 2px;
}

.main-layout__header-title {
  font-size: 17px;
  font-weight: 700;
  color: var(--susu-ink);
  letter-spacing: 0;
}

.main-layout__header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.main-layout__user-trigger {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 6px 14px;
  border-radius: 8px;
  background: var(--susu-surface);
  backdrop-filter: blur(10px);
  border: 1px solid var(--susu-border-glass);
  color: var(--susu-ink);
  box-shadow: 0 2px 8px rgba(183, 50, 92, 0.06), inset 0 1px 0 var(--susu-border-glass);
  transition: all 0.22s ease;
}

.main-layout__user-trigger:hover {
  background: var(--susu-surface-strong);
  border-color: rgba(255, 91, 138, 0.4);
  box-shadow: 0 4px 14px rgba(255, 91, 138, 0.18);
  transform: translateY(-1px);
}

.main-layout__avatar-wrap {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: #a82c51;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  box-shadow: 0 2px 6px rgba(255, 91, 138, 0.35);
}

.main-layout__username {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--susu-ink);
}

.main-layout__role {
  margin-left: 2px;
}

.main-layout__main {
  padding: 24px;
  overflow: auto;
  position: relative;
  z-index: 1;
}

@media (max-width: 768px) {
  .main-layout__sidebar {
    position: absolute;
    top: 0;
    bottom: 0;
    left: 0;
  }

  .main-layout__body {
    min-width: 0;
  }

  .main-layout__header {
    padding: 0 12px;
  }

  .main-layout__main {
    padding: 16px 12px;
  }

  .main-layout__username,
  .main-layout__role {
    display: none;
  }
}
</style>
