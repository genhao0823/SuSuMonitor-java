package com.susumonitor.data.security

import java.util.Base64

/**
 * 加密令牌的持久化格式（纯函数，JVM 单测可覆盖）：
 *
 * 存储值为 `enc:v1:` 前缀 + Base64(iv || ciphertext)，iv 为 GCM 12 字节 nonce。
 * 无前缀的值视为历史明文（旧版本升级兼容，读取后下次保存即覆盖为密文）。
 */
object TokenCipherFormat {

    const val PREFIX = "enc:v1:"

    /** GCM 推荐 nonce 长度（96 bit）。 */
    const val IV_LENGTH = 12

    /** 拼接 IV 与密文并做 Base64（NO_WRAP）。 */
    fun encode(iv: ByteArray, ciphertext: ByteArray): String =
        PREFIX + Base64.getEncoder().encodeToString(iv + ciphertext)

    /**
     * 解析存储值；格式非法（前缀缺失、Base64 损坏、IV 长度错误、空密文）返回 null。
     *
     * @return Pair(iv, ciphertext)，null 表示格式非法
     */
    fun decode(payload: String): Pair<ByteArray, ByteArray>? {
        if (!payload.startsWith(PREFIX)) return null
        val raw = try {
            Base64.getDecoder().decode(payload.removePrefix(PREFIX))
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (raw.size <= IV_LENGTH) return null
        return raw.copyOfRange(0, IV_LENGTH) to raw.copyOfRange(IV_LENGTH, raw.size)
    }
}
