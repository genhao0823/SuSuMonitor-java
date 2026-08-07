package com.susumonitor.server.common;

/**
 * 统一 REST 响应封装。
 *
 * @param <T> 响应数据类型
 */
public class ApiResponse<T> {

    private final int code;

    private final String message;

    private final T data;

    /**
     * 构造统一响应对象。
     *
     * @param code 业务状态码
     * @param message 响应消息
     * @param data 响应数据
     */
    private ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 创建成功响应，携带业务数据。
     *
     * @param data 响应数据
     * @param <T> 数据类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    /**
     * 创建错误响应，使用错误码的默认消息。
     *
     * @param errorCode 业务错误码
     * @return 错误响应
     */
    public static ApiResponse<Void> error(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /**
     * 创建错误响应，使用自定义错误消息覆盖错误码默认消息。
     *
     * @param errorCode 业务错误码
     * @param message 自定义错误消息
     * @return 错误响应
     */
    public static ApiResponse<Void> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getCode(), message, null);
    }

    /**
     * 获取业务状态码。
     *
     * @return 业务状态码
     */
    public int getCode() {
        return code;
    }

    /**
     * 获取响应消息。
     *
     * @return 响应消息
     */
    public String getMessage() {
        return message;
    }

    /**
     * 获取响应数据。
     *
     * @return 响应数据
     */
    public T getData() {
        return data;
    }
}
