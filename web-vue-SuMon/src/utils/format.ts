/**
 * 时间格式化与状态显示工具。
 * 任何 UI 文案应优先复用本文件的格式化函数,避免在多个 View 中重复实现。
 */

/**
 * 将 ISO 时间字符串格式化为本地时区简洁展示。
 *
 * @param iso ISO8601 字符串,非法或 null 时返回 '-'
 * @returns 本地时区的 `YYYY-MM-DD HH:mm:ss` 形式
 */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) {
    return '-'
  }
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) {
    return '-'
  }
  const pad = (n: number) => n.toString().padStart(2, '0')
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  )
}

/**
 * 服务器状态 → Element Plus Tag type。
 *
 * @param kind online / offline / unknown
 * @returns 对应的 ElTag type
 */
export function serverStatusTagType(
  kind: 'online' | 'offline' | 'unknown'
): 'success' | 'info' | 'warning' {
  if (kind === 'online') {
    return 'success'
  }
  if (kind === 'offline') {
    return 'info'
  }
  return 'warning'
}

/**
 * 服务器状态 → 中文展示文案。
 */
export function serverStatusLabel(
  kind: 'online' | 'offline' | 'unknown'
): string {
  if (kind === 'online') {
    return '在线'
  }
  if (kind === 'offline') {
    return '离线'
  }
  return '未知'
}

/**
 * 将字节数格式化为人类可读单位。
 *
 * @param bytes 字节数,空值返回 '-'
 * @returns 如 `512 B` / `1.5 KB` / `2.3 MB`
 */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes)) {
    return '-'
  }
  if (bytes < 1024) {
    return `${bytes} B`
  }
  const units = ['KB', 'MB', 'GB', 'TB']
  let value = bytes
  let unit = 'B'
  for (const next of units) {
    value /= 1024
    unit = next
    if (value < 1024) {
      break
    }
  }
  return `${value.toFixed(value >= 100 ? 0 : 1)} ${unit}`
}

/**
 * 用户角色 → 中文展示文案。
 */
export function userRoleLabel(role: 'admin' | 'user'): string {
  return role === 'admin' ? '管理员' : '普通用户'
}

/**
 * 审核状态 → 中文展示文案。
 */
export function reviewStatusLabel(
  status: 'pending' | 'approved' | 'rejected'
): string {
  if (status === 'approved') {
    return '已通过'
  }
  if (status === 'rejected') {
    return '已拒绝'
  }
  return '待审核'
}

/**
 * 审核状态 → Element Plus Tag type。
 */
export function reviewStatusTagType(
  status: 'pending' | 'approved' | 'rejected'
): 'success' | 'info' | 'warning' {
  if (status === 'approved') {
    return 'success'
  }
  if (status === 'rejected') {
    return 'info'
  }
  return 'warning'
}
