package com.susumonitor.api.interceptor

import okhttp3.logging.HttpLoggingInterceptor

/**
 * 构建调试日志拦截器。
 * 注意：Authorization 头默认被 OkHttp 日志器脱敏为 `Bearer ██`，不会泄露 JWT。
 */
fun createDebugLogInterceptor(): HttpLoggingInterceptor =
    HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }
