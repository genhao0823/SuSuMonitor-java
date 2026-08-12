package com.susumonitor.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端模拟器基础语义测试：字符写入、换行、CR、样式、滚动回退上限。
 */
class TerminalBufferTest {

    private fun newBuffer(cols: Int = 80, rows: Int = 24) = TerminalBuffer(cols, rows)

    /** 屏幕行文本（去除行尾空白格）。 */
    private fun screenText(buffer: TerminalBuffer, row: Int): String {
        val rows = buffer.visibleRows()
        return rows[rows.size - buffer.screenRows + row].joinToString("") { it.ch.toString() }.trimEnd()
    }

    private fun cell(buffer: TerminalBuffer, row: Int, col: Int): Cell {
        val rows = buffer.visibleRows()
        return rows[rows.size - buffer.screenRows + row][col]
    }

    @Test
    fun `plain text appears at cursor`() {
        val buffer = newBuffer()
        buffer.feed("hello".toByteArray())
        buffer.feed(" world".toByteArray())
        assertEquals("hello world", screenText(buffer, 0))
        assertEquals('h', cell(buffer, 0, 0).ch)
        assertEquals('d', cell(buffer, 0, 10).ch)
    }

    @Test
    fun `newline moves cursor to next row`() {
        val buffer = newBuffer()
        buffer.feed("line1\nline2".toByteArray())
        assertEquals("line1", screenText(buffer, 0))
        assertEquals("line2", screenText(buffer, 1))
    }

    @Test
    fun `ansi color codes are styled not stripped`() {
        val buffer = newBuffer()
        buffer.feed("\u001b[31mred\u001b[0m".toByteArray())
        assertEquals("red", screenText(buffer, 0))
        assertEquals(1, cell(buffer, 0, 0).fg)
        // SGR 0 复位后新字符恢复默认前景
        buffer.feed(" x".toByteArray())
        assertEquals(Cell.DEFAULT_FG, cell(buffer, 0, 4).fg)
    }

    @Test
    fun `carriage return overwrites current row from column zero`() {
        val buffer = newBuffer()
        buffer.feed("progress 10%".toByteArray())
        buffer.feed("\rprogress 50%\rprogress 100%".toByteArray())
        assertEquals("progress 100%", screenText(buffer, 0))
    }

    @Test
    fun `crlf pair keeps line content`() {
        // PTY 默认 onlcr：行以 \r\n 结尾，\r\n 应整体视为换行且保留行内容
        val buffer = newBuffer()
        buffer.feed("bin  lib\r\nboot opt\r\netc  srv\r\n".toByteArray())
        assertEquals("bin  lib", screenText(buffer, 0))
        assertEquals("boot opt", screenText(buffer, 1))
        assertEquals("etc  srv", screenText(buffer, 2))
    }

    @Test
    fun `crlf keeps prompt and command echo`() {
        val buffer = newBuffer()
        buffer.feed("root@host:/# ls\r\n".toByteArray())
        assertEquals("root@host:/# ls", screenText(buffer, 0))
    }

    @Test
    fun `backspace moves cursor left`() {
        val buffer = newBuffer()
        buffer.feed("abc".toByteArray())
        buffer.feed("\b".toByteArray())
        buffer.feed("Z".toByteArray())
        assertEquals("abZ", screenText(buffer, 0))
    }

    @Test
    fun `tab advances to next tab stop`() {
        val buffer = newBuffer()
        buffer.feed("a\tb".toByteArray())
        // a 在 col0，Tab 跳到 col8
        assertEquals('b', cell(buffer, 0, 8).ch)
    }

    @Test
    fun `characters wrap at last column`() {
        val buffer = newBuffer(cols = 4, rows = 3)
        buffer.feed("12345".toByteArray())
        // 第 4 格写满后 wrapPending，第 5 个字符落到下一行行首
        assertEquals("1234", screenText(buffer, 0))
        assertEquals("5", screenText(buffer, 1))
    }

    @Test
    fun `scrolling pushes rows to scrollback and caps at limit`() {
        val buffer = newBuffer(rows = 4)
        repeat(2100) { buffer.feed("line$it\n".toByteArray()) }
        assertTrue(buffer.scrollbackSize() <= 2000)
        // 最后一次换行触发滚动：line2099 位于屏幕倒数第二行
        assertEquals("line2099", screenText(buffer, 2))
    }

    @Test
    fun `resize keeps top-left content and truncates rows`() {
        val buffer = newBuffer(cols = 80, rows = 24)
        buffer.feed("hello world".toByteArray())
        buffer.resize(40, 10)
        assertEquals("hello world", screenText(buffer, 0))
        assertEquals(40, buffer.screenCols)
        assertEquals(10, buffer.screenRows)
    }

    @Test
    fun `clear resets screen scrollback and cursor`() {
        val buffer = newBuffer(rows = 3)
        buffer.feed("abc\ndef\nghi\n".toByteArray())
        buffer.clear()
        assertEquals(0, buffer.scrollbackSize())
        assertEquals(0, buffer.cursorScreenRow)
        assertEquals(0, buffer.cursorScreenCol)
        assertEquals("", screenText(buffer, 0))
    }

    @Test
    fun `utf8 multibyte characters decode correctly`() {
        val buffer = newBuffer()
        buffer.feed("涂山苏苏".toByteArray(Charsets.UTF_8))
        assertEquals("涂山苏苏", screenText(buffer, 0))
        assertEquals('苏', cell(buffer, 0, 2).ch)
    }
}
