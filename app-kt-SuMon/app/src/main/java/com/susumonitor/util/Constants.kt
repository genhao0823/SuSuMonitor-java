package com.susumonitor.util

/**
 * 后端连接配置（固定云端地址，用户已确认）。
 */
object Constants {
    /** REST API 基础地址（HTTPS）。 */
    const val BASE_URL = "https://genhaosan.online"

    /** Monitor WebSocket 通道地址（WSS，经 nginx TLS 反代）。 */
    const val WS_URL = "wss://genhaosan.online/ws/monitor"

    /** 每页默认条数。 */
    const val DEFAULT_PAGE_SIZE = 20
}
