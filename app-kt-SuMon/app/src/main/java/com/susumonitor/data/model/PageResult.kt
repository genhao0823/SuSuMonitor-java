package com.susumonitor.data.model

import kotlinx.serialization.Serializable

/**
 * 分页结果，与 OpenAPI `PageResult` schema 对齐。
 */
@Serializable
data class PageResult<T>(
    val items: List<T>,
    val total: Long,
    val page: Long,
    val page_size: Long,
)
