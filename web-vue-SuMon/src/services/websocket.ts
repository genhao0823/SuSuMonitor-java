import type { AlertPushPayload, ServerStatusPushPayload } from '@/types/api'
import { isAlertPush } from '@/api/alert'
import type { MetricsLatest } from '@/types/metrics'
import { issueMonitorTicket } from '@/api/websocket'
import { newCorrelationId } from '@/api/client'

/**
 * `/ws/monitor` 通用消息帧。
 * 仅声明本客户端关心的字段,其余未知字段不影响处理。
 */
interface MonitorMessage {
  type: string
  payload?: unknown
}

/** 校验 Monitor 推送的服务器状态转换载荷。 */
function isServerStatusPush(value: unknown): value is ServerStatusPushPayload {
  if (value === null || typeof value !== 'object') return false
  const payload = value as Record<string, unknown>
  return typeof payload.server_id === 'number'
    && (payload.status === 'online' || payload.status === 'offline' || payload.status === 'unknown')
    && (payload.agent_status === 'online' || payload.agent_status === 'offline')
    && (typeof payload.last_heartbeat_at === 'string' || payload.last_heartbeat_at === null)
}

/**
 * 浏览器 Monitor WebSocket 客户端,使用一次性 ticket,不在 URL 中携带长期 JWT。
 *
 * 职责单一:
 * - 申请一次性 ticket 并建立 WebSocket;
 * - 仅做"消息类型 -> 回调"的分派,不维护任何业务状态;
 * - 调用方负责接收回调并写入 Store。
 *
 * 消息类型处理:
 * - `metrics.update` -> `onMetrics(metrics)`
 * - `alert.push`     -> `onAlertPush(payload)`(可选,仅告警页传入)
 * - `server.status.update` -> `onServerStatus(payload)`(可选,仅实时监控页传入)
 * - `terminal.*`     -> `onTerminalMessage(frame)`(可选,仅终端页传入)
 * - 其他/非法       -> 忽略
 */
export class MonitorWebSocket {
  private socket: WebSocket | null = null
  private retryTimer: ReturnType<typeof setTimeout> | null = null
  private stopped = false
  private activeServerId: number | null = null

  constructor(
    private readonly onMetrics: (value: MetricsLatest) => void,
    private readonly onConnected: (value: boolean) => void,
    /**
     * 可选告警回调。MetricsView 不传,保持现有 metrics.update 路径完全不变。
     * AlertRecordsView 传入后,收到 `alert.push` 帧时通过 `isAlertPush` 守卫
     * 校验 payload 形态再回调。
     */
    private readonly onAlertPush?: (payload: AlertPushPayload) => void,
    /**
     * 可选 socket 就绪回调(MVP-7 T4 终端复用 channel 用)。
     * 在 socket.onopen 触发后、metrics.subscribe 之前调用,TerminalWebSocket 可在
     * 此处建链并缓存对 socket 的引用。
     */
    private readonly onSocketReady?: (socket: WebSocket) => void,
    /**
     * 可选终端消息回调。TerminalView 传入后,任何 `terminal.*` 帧(含 opened/output/closed/error)
     * 都会原样转发;TerminalWebSocket 自行做 payload 校验与回调分派。
     */
    private readonly onTerminalMessage?: (frame: MonitorMessage) => void,
    /** 可选服务器状态回调，仅实时监控页消费 Agent 在线/离线转换。 */
    private readonly onServerStatus?: (payload: ServerStatusPushPayload) => void
  ) {}

  /**
   * 当前正在订阅的服务器 ID。
   * 调用方(尤其告警页)在切换 server_id 前可据此判断是否需要重新订阅。
   */
  get currentServerId(): number | null {
    return this.activeServerId
  }

  connect(serverId: number): void {
    this.activeServerId = serverId
    this.stopped = false
    void this.open(serverId)
  }

  disconnect(): void {
    this.stopped = true
    if (this.retryTimer !== null) {
      clearTimeout(this.retryTimer)
      this.retryTimer = null
    }
    this.sendUnsubscribe()
    this.socket?.close()
    this.socket = null
    this.activeServerId = null
    this.onConnected(false)
  }

  /**
   * 在 socket 已打开且存在当前订阅时,发送 `metrics.unsubscribe` 帧。
   * 协议允许重复 unsubscribe 为幂等操作。
   */
  private sendUnsubscribe(): void {
    if (this.socket === null) return
    if (this.socket.readyState !== WebSocket.OPEN) return
    if (this.activeServerId === null) return
    try {
      this.socket.send(JSON.stringify({
        type: 'metrics.unsubscribe',
        message_id: newCorrelationId(),
        payload: { server_id: this.activeServerId }
      }))
    } catch {
      // send 失败时直接关闭即可,无需告警。
    }
  }

  private async open(serverId: number): Promise<void> {
    if (this.stopped) return
    let ticket: string
    try {
      const response = await issueMonitorTicket()
      if (!response.data) throw new Error('Monitor ticket missing')
      ticket = response.data.ticket
    } catch {
      this.onConnected(false)
      if (!this.stopped) this.retryTimer = setTimeout(() => void this.open(serverId), 3000)
      return
    }
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    this.socket = new WebSocket(`${protocol}//${window.location.host}/ws/monitor?ticket=${encodeURIComponent(ticket)}`)
    this.socket.onopen = () => {
      this.onConnected(true)
      // 让订阅者(终端)先于 metrics.subscribe 拿到 socket,避免竞态
      if (this.onSocketReady !== undefined && this.socket !== null) {
        try {
          this.onSocketReady(this.socket)
        } catch {
          // 订阅者抛错不影响主流程
        }
      }
      this.socket?.send(JSON.stringify({
        type: 'metrics.subscribe',
        message_id: newCorrelationId(),
        payload: { server_id: serverId }
      }))
    }
    this.socket.onmessage = (event) => {
      let message: MonitorMessage
      try {
        message = JSON.parse(event.data) as MonitorMessage
      } catch {
        // 非法 JSON 不击穿页面,直接忽略。
        return
      }
      if (message.type === 'metrics.update') {
        const metrics = (message.payload as { metrics?: MetricsLatest } | undefined)?.metrics
        if (metrics !== undefined) this.onMetrics(metrics)
        return
      }
      if (message.type === 'alert.push' && this.onAlertPush !== undefined) {
        if (isAlertPush(message.payload)) {
          this.onAlertPush(message.payload)
        }
        return
      }
      if (message.type === 'server.status.update' && this.onServerStatus !== undefined) {
        if (isServerStatusPush(message.payload)) {
          this.onServerStatus(message.payload)
        }
        return
      }
      if (typeof message.type === 'string' && message.type.startsWith('terminal.')) {
        if (this.onTerminalMessage !== undefined) {
          this.onTerminalMessage(message)
        }
      }
    }
    this.socket.onclose = () => {
      this.onConnected(false)
      if (!this.stopped) {
        this.retryTimer = setTimeout(() => void this.open(serverId), 3000)
      }
    }
  }
}