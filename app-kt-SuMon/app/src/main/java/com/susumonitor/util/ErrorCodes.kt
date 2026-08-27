package com.susumonitor.util

/**
 * 后端数字业务错误码常量，与 `server-java-SuMon/common/ErrorCode.java` 对齐。
 * 客户端按 code 分支而非解析 message 文本。
 */
object ErrorCodes {
    const val SUCCESS = 0
    const val BAD_REQUEST = 40000
    const val INVALID_USERNAME_OR_PASSWORD = 40001
    const val INVALID_REQUEST_PARAMETER = 40002
    const val TERMINAL_INVALID_PAYLOAD = 40003
    const val UNAUTHORIZED = 40100
    const val FORBIDDEN = 40300
    const val SSH_TARGET_FORBIDDEN = 40301
    const val TERMINAL_ACCESS_DENIED = 40302
    const val RESOURCE_NOT_FOUND = 40400
    const val TERMINAL_SESSION_NOT_FOUND = 40403
    const val RESOURCE_CONFLICT = 40900
    const val SSH_HOST_KEY_NOT_CONFIRMED = 40901
    const val SSH_HOST_KEY_MISMATCH = 40902
    const val TERMINAL_SESSION_STATE_CONFLICT = 40903
    const val TERMINAL_AGENT_OFFLINE = 40904
    const val SSH_CONNECTION_LIMIT_REACHED = 42900
    const val AGENT_CONNECTION_LIMIT_REACHED = 42901
    const val AGENT_MESSAGE_RATE_LIMIT_REACHED = 42902
    const val TERMINAL_SESSION_LIMIT_REACHED = 42903
    const val TERMINAL_MESSAGE_LIMIT_REACHED = 42904
    const val INTERNAL_SERVER_ERROR = 50000
    const val DATABASE_ERROR = 50001
    const val SSH_CONNECTION_FAILED = 50002
    const val SSH_AUTHENTICATION_FAILED = 50003
    const val RABBITMQ_UNAVAILABLE = 50301
    const val SSH_CONNECTION_TIMEOUT = 50400
}
