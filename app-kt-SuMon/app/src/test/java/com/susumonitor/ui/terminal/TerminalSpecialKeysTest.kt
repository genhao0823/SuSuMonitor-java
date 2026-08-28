package com.susumonitor.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端功能键行（SpecialKeys）契约测试：
 * 控制字节正确、无重复字节序列、UTF-8 编码非空。
 */
class TerminalSpecialKeysTest {

    @Test
    fun `ctrl sequences map to correct control bytes`() {
        val bytes = SpecialKeys.toMap()
        assertEquals("\u0003", bytes["Ctrl+C"])
        assertEquals("\u001a", bytes["Ctrl+Z"])
        assertEquals("\u000c", bytes["Ctrl+L"])
        assertEquals("\u001b", bytes["Esc"])
        assertEquals("\t", bytes["Tab"])
    }

    @Test
    fun `direction keys map to escape sequences`() {
        val bytes = SpecialKeys.toMap()
        assertEquals("\u001b[A", bytes["↑"])
        assertEquals("\u001b[B", bytes["↓"])
        assertEquals("\u001b[D", bytes["←"])
        assertEquals("\u001b[C", bytes["→"])
    }

    @Test
    fun `no duplicate byte sequences across keys`() {
        val seen = HashSet<String>()
        for ((_, seq) in SpecialKeys) {
            assertTrue("重复字节序列: ${seq.toByteArray(Charsets.UTF_8).toList()}", seen.add(seq))
        }
    }

    @Test
    fun `all keys encode to non-empty utf-8`() {
        for ((label, seq) in SpecialKeys) {
            val bytes = seq.toByteArray(Charsets.UTF_8)
            assertTrue("键 $label 编码为空", bytes.isNotEmpty())
        }
    }
}
