package com.susumonitor.data.security

/**
 * 登录令牌加密契约：JWT 落盘前用 Keystore 密钥加密，防止备份/root 读取直接泄露会话凭据。
 *
 * 加密值经 [TokenCipherFormat] 携带 `enc:v1:` 前缀；读取时由 SessionStore 判断
 * 是否为历史明文（无前缀）做兼容。
 */
interface TokenCipher {

    /** 加密并返回持久化字符串（含格式前缀）。 */
    fun encrypt(plaintext: String): String

    /**
     * 解密持久化字符串；数据损坏或密钥不可用（如设备备份迁移）时返回 null，
     * 调用方将视为无会话并引导重新登录。
     */
    fun decrypt(payload: String): String?
}
