package com.susumonitor.data.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 验证加密令牌持久化格式：前缀、Base64 往返、非法输入容错。 */
class TokenCipherFormatTest {

    @Test
    fun `encode then decode should roundtrip iv and ciphertext`() {
        val iv = ByteArray(12) { it.toByte() }
        val ciphertext = byteArrayOf(1, 2, 3, 4, 5)

        val encoded = TokenCipherFormat.encode(iv, ciphertext)
        val decoded = TokenCipherFormat.decode(encoded)

        assertEquals(true, encoded.startsWith(TokenCipherFormat.PREFIX))
        assertEquals(iv.toList(), decoded!!.first.toList())
        assertArrayEquals(ciphertext, decoded.second)
    }

    @Test
    fun `decode should reject payload without prefix`() {
        assertNull(TokenCipherFormat.decode("plain-legacy-token"))
    }

    @Test
    fun `decode should reject corrupted base64`() {
        assertNull(TokenCipherFormat.decode("enc:v1:!!!not-base64!!!"))
    }

    @Test
    fun `decode should reject iv-only payload`() {
        val iv = ByteArray(12)
        val tooShort = TokenCipherFormat.encode(iv, ByteArray(0))
        // 手工构造无密文的载荷：前缀 + 12 字节 IV 的 Base64
        val ivOnly = TokenCipherFormat.PREFIX + java.util.Base64.getEncoder()
            .encodeToString(ByteArray(12))
        assertNull(TokenCipherFormat.decode(tooShort))
        assertNull(TokenCipherFormat.decode(ivOnly))
    }
}
