package com.susumonitor.server.common;

import org.springframework.http.HttpStatus;

/**
 * 全项目错误码枚举，由 API 契约统一定义。
 */
public enum ErrorCode {

    SUCCESS(0, "success", HttpStatus.OK),
    BAD_REQUEST(40000, "bad request", HttpStatus.BAD_REQUEST),
    INVALID_USERNAME_OR_PASSWORD(40001, "invalid username or password", HttpStatus.BAD_REQUEST),
    INVALID_REQUEST_PARAMETER(40002, "invalid request parameter", HttpStatus.BAD_REQUEST),
    TERMINAL_INVALID_PAYLOAD(40003, "terminal invalid payload", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(40100, "unauthorized", HttpStatus.UNAUTHORIZED),
    FORBIDDEN(40300, "forbidden", HttpStatus.FORBIDDEN),
    TERMINAL_ACCESS_DENIED(40302, "terminal access denied", HttpStatus.FORBIDDEN),
    RESOURCE_NOT_FOUND(40400, "resource not found", HttpStatus.NOT_FOUND),
    TERMINAL_SESSION_NOT_FOUND(40403, "terminal session not found", HttpStatus.NOT_FOUND),
    RESOURCE_CONFLICT(40900, "resource conflict", HttpStatus.CONFLICT),
    SSH_HOST_KEY_NOT_CONFIRMED(40901, "ssh host key not confirmed", HttpStatus.CONFLICT),
    SSH_HOST_KEY_MISMATCH(40902, "ssh host key mismatch", HttpStatus.CONFLICT),
    TERMINAL_SESSION_STATE_CONFLICT(40903, "terminal session state conflict", HttpStatus.CONFLICT),
    TERMINAL_AGENT_OFFLINE(40904, "terminal agent offline", HttpStatus.CONFLICT),
    SSH_TARGET_FORBIDDEN(40301, "ssh target forbidden", HttpStatus.FORBIDDEN),
    SSH_CONNECTION_LIMIT_REACHED(42900, "ssh connection limit reached", HttpStatus.TOO_MANY_REQUESTS),
    AGENT_CONNECTION_LIMIT_REACHED(42901, "agent connection limit reached", HttpStatus.TOO_MANY_REQUESTS),
    AGENT_MESSAGE_RATE_LIMIT_REACHED(42902, "agent message rate limit reached", HttpStatus.TOO_MANY_REQUESTS),
    TERMINAL_SESSION_LIMIT_REACHED(42903, "terminal session limit reached", HttpStatus.TOO_MANY_REQUESTS),
    TERMINAL_MESSAGE_LIMIT_REACHED(42904, "terminal message limit reached", HttpStatus.TOO_MANY_REQUESTS),
    LOGIN_RATE_LIMIT_REACHED(42905, "login rate limit reached", HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL_SERVER_ERROR(50000, "internal server error", HttpStatus.INTERNAL_SERVER_ERROR),
    DATABASE_ERROR(50001, "database error", HttpStatus.INTERNAL_SERVER_ERROR),
    SSH_CONNECTION_FAILED(50002, "ssh connection failed", HttpStatus.BAD_GATEWAY),
    SSH_AUTHENTICATION_FAILED(50003, "ssh authentication failed", HttpStatus.BAD_GATEWAY),
    RABBITMQ_UNAVAILABLE(50301, "rabbitmq unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    REDIS_UNAVAILABLE(50302, "redis unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    SSH_CONNECTION_TIMEOUT(50400, "ssh connection timeout", HttpStatus.GATEWAY_TIMEOUT);

    private final int code;

    private final String message;

    private final HttpStatus httpStatus;

    /**
     * 构造错误码枚举项。
     *
     * @param code 数字错误码
     * @param message 默认错误消息
     * @param httpStatus 对应的 HTTP 状态码
     */
    ErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    /**
     * 获取数字错误码。
     *
     * @return 数字错误码
     */
    public int getCode() {
        return code;
    }

    /**
     * 获取默认错误消息。
     *
     * @return 默认错误消息
     */
    public String getMessage() {
        return message;
    }

    /**
     * 获取对应的 HTTP 状态码。
     *
     * @return HTTP 状态码
     */
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
