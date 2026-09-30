package com.susumonitor.server.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 为每个请求生成可信请求 ID，并安全透传可选的客户端关联 ID。
 */
@Component
// 让追踪信息先于 Spring Security 建立，确保 401 和 403 响应也包含请求 ID。
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-ID";

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    public static final String REQUEST_ID_MDC_KEY = "request_id";

    public static final String CORRELATION_ID_MDC_KEY = "correlation_id";

    private static final Pattern CORRELATION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /**
     * 生成请求 ID，并在关联 ID合法时写入响应和日志上下文。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param filterChain 过滤器链
     * @throws ServletException 下游 Servlet 处理异常
     * @throws IOException HTTP 输入输出异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        MDC.put(REQUEST_ID_MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        if (validCorrelationId(correlationId)) {
            MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
            response.setHeader(CORRELATION_ID_HEADER, correlationId);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(REQUEST_ID_MDC_KEY);
            MDC.remove(CORRELATION_ID_MDC_KEY);
        }
    }

    /**
     * 校验客户端关联 ID，防止不可信或超长内容进入日志上下文。
     *
     * @param correlationId 客户端关联 ID
     * @return 是否符合静态 OpenAPI 契约
     */
    private boolean validCorrelationId(String correlationId) {
        return correlationId != null && CORRELATION_ID_PATTERN.matcher(correlationId).matches();
    }
}
