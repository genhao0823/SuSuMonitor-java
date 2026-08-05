package com.susumonitor.util

/**
 * 后端连接配置（固定云端地址，用户已确认）。
 * 生产切 HTTPS 后仅需修改此处，同时收紧 network_security_config.xml。
 */
object Constants {
    /** REST API 基础地址（nginx 80 反代 /api/）。 */
    const val BASE_URL = "http://82.156.245.102"

    /** Monitor WebSocket 通道地址（明文 ws，经 nginx Upgrade 反代）。 */
    const val WS_URL = "ws://82.156.245.102/ws/monitor"

    /** 每页默认条数。 */
    const val DEFAULT_PAGE_SIZE = 20
}
