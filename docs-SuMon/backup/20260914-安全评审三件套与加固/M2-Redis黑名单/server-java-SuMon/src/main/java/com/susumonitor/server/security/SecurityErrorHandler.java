package com.susumonitor.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susumonitor.server.common.ApiResponse;
import com.susumonitor.server.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 将 Spring Security 的认证和授权失败转换为项目统一 JSON 响应。
 */
// 将处理器注册为 Spring Bean，供过滤器和安全配置共享。
@Component
// 自动生成 ObjectMapper 构造注入方法。
@RequiredArgsConstructor
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    /**
     * 返回统一 401，并声明 Bearer 认证方案。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param exception 认证异常
     * @throws IOException 响应写入失败
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        writeError(response, ErrorCode.UNAUTHORIZED);
    }

    /**
     * 返回统一 403，避免将内部授权异常文本暴露给客户端。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param exception 授权异常
     * @throws IOException 响应写入失败
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        writeError(response, ErrorCode.FORBIDDEN);
    }

    /**
     * 为过滤器中的数据库故障返回统一数据库错误。
     *
     * @param response HTTP 响应
     * @throws IOException 响应写入失败
     */
    public void writeDatabaseError(HttpServletResponse response) throws IOException {
        writeError(response, ErrorCode.DATABASE_ERROR);
    }

    /**
     * 为过滤器中的未知故障返回统一内部错误。
     *
     * @param response HTTP 响应
     * @throws IOException 响应写入失败
     */
    public void writeInternalError(HttpServletResponse response) throws IOException {
        writeError(response, ErrorCode.INTERNAL_SERVER_ERROR);
    }

    /**
     * 写入固定结构的 JSON 错误响应，同时保留外层追踪过滤器设置的 Header。
     *
     * @param response HTTP 响应
     * @param errorCode 错误码
     * @throws IOException 响应写入失败
     */
    private void writeError(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(errorCode));
    }
}
