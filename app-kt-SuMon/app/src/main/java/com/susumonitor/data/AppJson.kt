package com.susumonitor.data

import kotlinx.serialization.json.Json

/** 全局 kotlinx.serialization 配置：忽略未知字段以容忍契约演进。 */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    isLenient = true
}
