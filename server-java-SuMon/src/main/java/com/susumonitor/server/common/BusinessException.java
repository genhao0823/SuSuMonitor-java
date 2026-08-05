package com.susumonitor.server.common;

/**
 * Business exception carrying a stable API error code.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * 创建一个业务异常，根据错误码设置异常消息。
     *
     * @param errorCode 业务错误码，决定 HTTP 状态和响应文案
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /**
     * 创建一个业务异常，携带原始底层异常 cause，用于异常翻译场景。
     *
     * @param errorCode 业务错误码，决定 HTTP 状态和响应文案
     * @param cause     原始异常（如 DuplicateKeyException），保留技术原因便于排查
     */
    public BusinessException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }

    /**
     * 获取异常携带的业务错误码。
     *
     * @return 业务错误码，供 GlobalExceptionHandler 决定响应状态和文案
     */
    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
