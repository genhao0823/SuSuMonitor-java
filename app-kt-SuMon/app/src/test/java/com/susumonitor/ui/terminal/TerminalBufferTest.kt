package com.susumonitor.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端行缓冲 feedTerminal 测试：UTF-8 解码 + ANSI 剥离 + 换行处理。
 */
class TerminalBufferTest {

    @Test
    fun `plain text appends to line`() {
        val lines = mutableListOf<String>()
        lines.feedTerminal("hello".toByteArray())
        lines.feedTerminal(" world".toByteArray())
        assertEquals(listOf("hello world"), lines)
    }

    @Test
    fun `newline starts new line`() {
        val lines = mutableListOf<String>()
        lines.feedTerminal("line1\nline2".toByteArray())
        assertEquals(listOf("line1", "line2"), lines)
    }

    @Test
    fun `ansi color codes stripped`() {
        val lines = mutableListOf<String>()
        lines.feedTerminal("\u001b[31mred\u001b[0m".toByteArray())
        assertEquals(listOf("red"), lines)
    }

    @Test
    fun `carriage return overwrites current line`() {
        val lines = mutableListOf<String>()
        lines.feedTerminal("progress 10%".toByteArray())
        lines.feedTerminal("\rprogress 50%\rprogress 100%".toByteArray())
        assertEquals(listOf("progress 100%"), lines)
    }

    @Test
    fun `crlf pair keeps line content`() {
        // PTY 默认 onlcr：行以 \r\n 结尾，\r\n 应整体视为换行且保留行内容
        val lines = mutableListOf<String>()
        lines.feedTerminal("bin  lib\r\nboot opt\r\netc  srv\r\n".toByteArray())
        assertEquals(listOf("bin  lib", "boot opt", "etc  srv"), lines)
    }

    @Test
    fun `crlf keeps prompt and command echo`() {
        // 提示符 + 命令回显 + \r\n：提示符行内容不能被清空
        val lines = mutableListOf<String>()
        lines.feedTerminal("root@host:/# ls\r\n".toByteArray())
        assertEquals(listOf("root@host:/# ls"), lines)
    }

    @Test
    fun `line count is capped`() {
        val lines = mutableListOf<String>()
        repeat(2500) { lines.feedTerminal("line$it\n".toByteArray()) }
        assertTrue(lines.size <= 2000)
    }
}
