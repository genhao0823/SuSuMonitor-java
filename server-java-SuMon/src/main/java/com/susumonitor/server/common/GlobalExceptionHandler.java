package com.susumonitor.server.common;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.validation.FieldError;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Converts exceptions into the unified API response shape.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理业务异常，从异常中提取 ErrorCode 并返回对应的 HTTP 状态和响应体。
     *
     * @param exception 业务异常，携带 ErrorCode
     * @return 统一错误响应，状态码和文案由 ErrorCode 决定
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        LOGGER.warn("Business exception: {}", errorCode.getMessage());
        return ResponseEntity.status(errorCode.getHttpStatus()).body(ApiResponse.error(errorCode));
    }

    /**
     * 处理 @Valid 请求体校验失败，仅记录校验不通过的字段名，不记录字段值。
     *
     * @param exception 请求体校验异常，包含校验失败的字段信息
     * @return 统一参数错误响应（400）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException exception) {
        List<String> invalidFields = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(FieldError::getField)
                .distinct()
                .sorted()
                .toList();
        LOGGER.warn("Request validation failed for fields: {}", invalidFields);

        ErrorCode errorCode = ErrorCode.INVALID_REQUEST_PARAMETER;
        return ResponseEntity.status(errorCode.getHttpStatus()).body(ApiResponse.error(errorCode));
    }

    /**
     * 处理 @Validated 参数/路径参数校验失败，仅记录约束路径，不记录参数实际值。
     *
     * @param exception 约束校验异常，包含校验失败的参数路径
     * @return 统一参数错误响应（400）
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolationException(
            ConstraintViolationException exception) {
        List<String> invalidPaths = exception.getConstraintViolations()
                .stream()
                .map(violation -> violation.getPropertyPath().toString())
                .distinct()
                .sorted()
                .toList();
        LOGGER.warn("Constraint validation failed for paths: {}", invalidPaths);

        ErrorCode errorCode = ErrorCode.INVALID_REQUEST_PARAMETER;
        return ResponseEntity.status(errorCode.getHttpStatus()).body(ApiResponse.error(errorCode));
    }

    /**
     * 将缺失的必填查询参数转换为统一参数错误，不记录请求参数值。
     *
     * @param exception 必填查询参数缺失异常
     * @return 统一参数错误响应
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingServletRequestParameterException(
            MissingServletRequestParameterException exception) {
        LOGGER.warn("Required request parameter is missing: {}", exception.getParameterName());
        ErrorCode errorCode = ErrorCode.INVALID_REQUEST_PARAMETER;
        return ResponseEntity.status(errorCode.getHttpStatus()).body(ApiResponse.error(errorCode));
    }

    /**
     * 将查询参数类型或时间格式转换失败统一映射为参数错误，不记录可能敏感的原始值。
     *
     * @param exception 查询参数类型转换异常
     * @return 统一参数错误响应
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentTypeMismatchException(
            MethodArgumentTypeMismatchException exception) {
        LOGGER.warn("Request parameter type conversion failed: {}", exception.getName());
        ErrorCode errorCode = ErrorCode.INVALID_REQUEST_PARAMETER;
        return ResponseEntity.status(errorCode.getHttpStatus()).body(ApiResponse.error(errorCode));
    }

    /**
     * 将无法解析的 JSON 请求体转换为参数错误，不记录可能包含敏感字段的原始请求内容。
     *
     * @param exception JSON 反序列化异常
     * @return 统一参数错误响应
     */
    // 捕获 JSON 语法或类型错误，避免错误进入通用 500 处理。
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException exception) {
        LOGGER.warn("Request body could not be parsed");
        ErrorCode errorCode = ErrorCode.INVALID_REQUEST_PARAMETER;
        return ResponseEntity.status(errorCode.getHttpStatus()).body(ApiResponse.error(errorCode));
    }

    /**
     * 兜底处理所有未匹配的异常，返回 500 内部错误，完整堆栈仅记录到日志。
     *
     * @param exception 未处理的异常
     * @return 固定内部错误响应（500）
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception exception) {
        LOGGER.error("Unhandled exception", exception);
        ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(errorCode.getHttpStatus()).body(ApiResponse.error(errorCode));
    }
}
