import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { MonitorWebSocket } from '@/services/websocket'
import * as ticketApi from '@/api/websocket'

/**
 * 简易 WebSocket mock,允许测试主动驱动 open/message/close 事件。
 */
class FakeWebSocket {
  static OPEN = 1
  static CLOSED = 3
  readyState = 0
  url: string
  sent: string[] = []
  onopen: ((event: Event) => void) | null = null
  onmessage: ((event: MessageEvent) => void) | null = null
  onclose: ((event: CloseEvent) => void) | null = null

  constructor(url: string) {
    this.url = url
  }

  send(payload: string): void {
    this.sent.push(payload)
  }

  close(): void {
    this.readyState = FakeWebSocket.CLOSED
    this.onclose?.(new CloseEvent('close'))
  }

  /** 触发 onopen(测试主动模拟连接成功)。 */
  emitOpen(): void {
    this.readyState = FakeWebSocket.OPEN
    this.onopen?.(new Event('open'))
  }

  /** 触发 onmessage(测试发送服务端帧)。 */
  emitMessage(payload: unknown): void {
    this.onmessage?.(new MessageEvent('message', { data: JSON.stringify(payload) }))
  }

  /** 触发 onmessage 但发送非法 JSON(测错误容忍)。 */
  emitRawMessage(raw: string): void {
    this.onmessage?.(new MessageEvent('message', { data: raw }))
  }

  /** 触发 onclose(模拟服务端关闭)。 */
  emitClose(): void {
    this.readyState = FakeWebSocket.CLOSED
    this.onclose?.(new CloseEvent('close'))
  }
}

/** 当前 mock 实例(供测试断言用)。 */
let lastSocket: FakeWebSocket | null = null

describe('MonitorWebSocket', () => {
  beforeEach(() => {
    lastSocket = null
    vi.spyOn(ticketApi, 'issueMonitorTicket').mockResolvedValue({
      code: 0,
      message: 'success',
      data: { ticket: 'test-ticket', expires_at: '2026-07-27T12:00:30Z' }
    })
    // 用 class mock 替换全局 WebSocket;构造签名只取第一个 url 参数即可。
    class WsCtor extends FakeWebSocket {
      constructor(url: string | URL) {
        super(typeof url === 'string' ? url : url.toString())
        lastSocket = this
      }
    }
    // 赋值给全局 WebSocket 是必要的(产品代码直接 new WebSocket),用 unknown 中转避开类型不兼容。
    (globalThis as unknown as { WebSocket: unknown }).WebSocket = WsCtor
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  it('connect 后 ticket 申请 + socket 建立 + subscribe 帧发出', async () => {
    const onMetrics = vi.fn()
    const onConnected = vi.fn()
    const onAlertPush = vi.fn()
    const ws = new MonitorWebSocket(onMetrics, onConnected, onAlertPush)
    ws.connect(42)
    // 微任务:等待 issueMonitorTicket + new WebSocket 完成
    await new Promise((r) => setTimeout(r, 0))
    expect(ticketApi.issueMonitorTicket).toHaveBeenCalledOnce()
    expect(lastSocket).not.toBeNull()
    expect(lastSocket?.url).toContain('ticket=test-ticket')
    expect(lastSocket?.url).toContain('/ws/monitor')
    expect(ws.currentServerId).toBe(42)
    // 手动触发 open
    lastSocket?.emitOpen()
    expect(onConnected).toHaveBeenCalledWith(true)
    const subscribeFrame = JSON.parse(lastSocket?.sent[0] ?? '{}')
    expect(subscribeFrame.type).toBe('metrics.subscribe')
    expect(subscribeFrame.payload.server_id).toBe(42)
  })

  it('metrics.update 帧调 onMetrics;alert.push 帧调 onAlertPush', async () => {
    const onMetrics = vi.fn()
    const onConnected = vi.fn()
    const onAlertPush = vi.fn()
    const ws = new MonitorWebSocket(onMetrics, onConnected, onAlertPush)
    ws.connect(1)
    await new Promise((r) => setTimeout(r, 0))
    lastSocket?.emitOpen()

    const metrics = { server_id: 1, cpu_percent: 50, memory_percent: null } as never
    lastSocket?.emitMessage({ type: 'metrics.update', payload: { server_id: 1, metrics } })
    expect(onMetrics).toHaveBeenCalledWith(metrics)

    lastSocket?.emitMessage({
      type: 'alert.push',
      payload: {
        server_id: 1,
        alert: { id: 1, rule_id: null, metric: 'cpu', current_value: 90, threshold_value: 80, level: 'warning', status: 'unread', triggered_at: '2026-07-22T00:00:00Z' }
      }
    })
    expect(onAlertPush).toHaveBeenCalledOnce()
    expect(onAlertPush.mock.calls[0]?.[0].server_id).toBe(1)
  })

  it('server.status.update 帧调 onServerStatus，非法 payload 被忽略', async () => {
    const onServerStatus = vi.fn()
    const ws = new MonitorWebSocket(vi.fn(), vi.fn(), undefined, undefined, undefined, onServerStatus)
    ws.connect(1)
    await new Promise((r) => setTimeout(r, 0))
    lastSocket?.emitOpen()

    const payload = {
      server_id: 1,
      status: 'offline',
      agent_status: 'offline',
      last_heartbeat_at: '2026-08-01T12:00:00.123456Z'
    }
    lastSocket?.emitMessage({ type: 'server.status.update', payload })
    expect(onServerStatus).toHaveBeenCalledWith(payload)

    lastSocket?.emitMessage({ type: 'server.status.update', payload: { server_id: 1 } })
    expect(onServerStatus).toHaveBeenCalledOnce()
  })

  it('未传 onAlertPush 时收到 alert.push 帧不会抛错', async () => {
    const ws = new MonitorWebSocket(vi.fn(), vi.fn())
    ws.connect(1)
    await new Promise((r) => setTimeout(r, 0))
    lastSocket?.emitOpen()
    expect(() => {
      lastSocket?.emitMessage({
        type: 'alert.push',
        payload: {
          server_id: 1,
          alert: { id: 1, rule_id: null, metric: 'cpu', current_value: 90, threshold_value: 80, level: 'warning', status: 'unread', triggered_at: '2026-07-22T00:00:00Z' }
        }
      })
    }).not.toThrow()
  })

  it('非法 JSON 帧不击穿页面', async () => {
    const onMetrics = vi.fn()
    const ws = new MonitorWebSocket(onMetrics, vi.fn())
    ws.connect(1)
    await new Promise((r) => setTimeout(r, 0))
    lastSocket?.emitOpen()
    expect(() => lastSocket?.emitRawMessage('{not-json')).not.toThrow()
    expect(onMetrics).not.toHaveBeenCalled()
  })

  it('alert.push 帧 payload 非法时 onAlertPush 不被调用', async () => {
    const onAlertPush = vi.fn()
    const ws = new MonitorWebSocket(vi.fn(), vi.fn(), onAlertPush)
    ws.connect(1)
    await new Promise((r) => setTimeout(r, 0))
    lastSocket?.emitOpen()
    lastSocket?.emitMessage({ type: 'alert.push', payload: { server_id: 'not-a-number' } })
    expect(onAlertPush).not.toHaveBeenCalled()
  })

  it('disconnect 在 socket OPEN 时发送 metrics.unsubscribe 帧', async () => {
    const ws = new MonitorWebSocket(vi.fn(), vi.fn())
    ws.connect(7)
    await new Promise((r) => setTimeout(r, 0))
    lastSocket?.emitOpen()
    expect(lastSocket?.sent).toHaveLength(1) // 只有 subscribe
    ws.disconnect()
    expect(lastSocket?.sent).toHaveLength(2)
    const unsubscribe = JSON.parse(lastSocket?.sent[1] ?? '{}')
    expect(unsubscribe.type).toBe('metrics.unsubscribe')
    expect(unsubscribe.payload.server_id).toBe(7)
    expect(ws.currentServerId).toBeNull()
  })

  it('disconnect 在 socket 未 OPEN 时不发 unsubscribe 帧', async () => {
    const ws = new MonitorWebSocket(vi.fn(), vi.fn())
    ws.connect(7)
    await new Promise((r) => setTimeout(r, 0))
    // 不 emitOpen,readyState 仍为 0
    ws.disconnect()
    expect(lastSocket?.sent).toHaveLength(0)
  })

  it('onclose 后 3 秒自动重连:重新申请 ticket + 新 socket', async () => {
    vi.useFakeTimers()
    const onConnected = vi.fn()
    const ws = new MonitorWebSocket(vi.fn(), onConnected)
    ws.connect(11)
    await vi.advanceTimersByTimeAsync(0)
    lastSocket?.emitOpen()
    expect(ticketApi.issueMonitorTicket).toHaveBeenCalledTimes(1)

    // 服务端关闭 → 触发 onclose → 启动 3 秒重试
    lastSocket?.emitClose()
    expect(onConnected).toHaveBeenLastCalledWith(false)

    // 推进 3 秒前不应有任何重连动作
    await vi.advanceTimersByTimeAsync(2999)
    expect(ticketApi.issueMonitorTicket).toHaveBeenCalledTimes(1)

    // 推进到 3 秒应触发重连
    await vi.advanceTimersByTimeAsync(1)
    expect(ticketApi.issueMonitorTicket).toHaveBeenCalledTimes(2)
    expect(lastSocket).not.toBeNull()
    expect(lastSocket?.url).toContain('/ws/monitor')
  })

  /* ------------------ MVP-7 T4 扩展点回归 ------------------ */

  it('onSocketReady 在 socket.onopen 后、metrics.subscribe 之前触发,回调拿到 WebSocket 实例', async () => {
    const onSocketReady = vi.fn()
    const ws = new MonitorWebSocket(vi.fn(), vi.fn(), undefined, onSocketReady)
    ws.connect(1)
    await new Promise((r) => setTimeout(r, 0))
    lastSocket?.emitOpen()
    expect(onSocketReady).toHaveBeenCalledOnce()
    expect(onSocketReady.mock.calls[0]?.[0]).toBe(lastSocket)
    // metrics.subscribe 仍在 onSocketReady 之后发出(避免终端 open 与 subscribe 竞态)
    const subscribe = JSON.parse(lastSocket?.sent[0] ?? '{}')
    expect(subscribe.type).toBe('metrics.subscribe')
  })

  it('onSocketReady 抛错时不击穿 metrics.subscribe 流程', async () => {
    const ws = new MonitorWebSocket(vi.fn(), vi.fn(), undefined, () => {
      throw new Error('terminal init boom')
    })
    ws.connect(1)
    await new Promise((r) => setTimeout(r, 0))
    expect(() => lastSocket?.emitOpen()).not.toThrow()
    const subscribe = JSON.parse(lastSocket?.sent[0] ?? '{}')
    expect(subscribe.type).toBe('metrics.subscribe')
  })

  it('terminal.* 帧调 onTerminalMessage;未传时不抛错', async () => {
    const onTerminalMessage = vi.fn()
    const ws = new MonitorWebSocket(
      vi.fn(),
      vi.fn(),
      undefined,
      undefined,
      onTerminalMessage
    )
    ws.connect(1)
    await new Promise((r) => setTimeout(r, 0))
    lastSocket?.emitOpen()
    lastSocket?.emitMessage({
      type: 'terminal.opened',
      payload: { server_id: 1, session_id: 'sess-1', shell: '/bin/bash' }
    })
    expect(onTerminalMessage).toHaveBeenCalledOnce()
    expect(onTerminalMessage.mock.calls[0]?.[0].type).toBe('terminal.opened')

    // 不传 onTerminalMessage:不应击穿
    const noTerm = new MonitorWebSocket(vi.fn(), vi.fn())
    noTerm.connect(1)
    await new Promise((r) => setTimeout(r, 0))
    lastSocket?.emitOpen()
    expect(() =>
      lastSocket?.emitMessage({
        type: 'terminal.output',
        payload: { server_id: 1, session_id: 'sess-1', data: 'aGVsbG8=' }
      })
    ).not.toThrow()
  })
})