package com.susumonitor.server.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 验证全局异常处理器的内建信号异常映射：未映射路径必须 404/40400，
 * 不允许落入 Exception 兜底误报 500（2026-09-14 联调缺陷回归）。
 */
class GlobalExceptionHandlerTests {

    /** NoResourceFoundException 映射为 HTTP 404 + 40400，而非兜底 500。 */
    @Test
    void noResourceFoundShouldMapToNotFound() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(60);
        NoResourceFoundException exception =
                new NoResourceFoundException(null, "api/no-such-path");

        ResponseEntity<ApiResponse<Void>> response = handler.handleNoResourceFound(exception);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(40400, response.getBody().getCode());
    }
}
