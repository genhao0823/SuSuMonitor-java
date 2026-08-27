package com.susumonitor.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANSI 转义序列解析测试：SGR、光标移动/定位、清除、滚动区、备用屏、状态报告、
 * 插入/删除字符与行、UTF-8/CSI 跨调用分片。
 */
class TerminalParserTest {

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

    // ---- SGR ----

    @Test
    fun `sgr colors foreground and background`() {
        val buffer = newBuffer()
        buffer.feed("\u001b[31;44mX".toByteArray())
        assertEquals(1, cell(buffer, 0, 0).fg)
        assertEquals(4, cell(buffer, 0, 0).bg)
    }

    @Test
    fun `sgr bright colors map to palette 8-15`() {
        val buffer = newBuffer()
        buffer.feed("\u001b[91;105mX".toByteArray())
        assertEquals(9, cell(buffer, 0, 0).fg)
        assertEquals(13, cell(buffer, 0, 0).bg)
    }

    @Test
    fun `sgr 256 color extended sequence`() {
        val buffer = newBuffer()
        buffer.feed("\u001b[38;5;196mX\u001b[48;5;21mY".toByteArray())
        assertEquals(196, cell(buffer, 0, 0).fg)
        assertEquals(Cell.DEFAULT_BG, cell(buffer, 0, 0).bg)
        assertEquals(196, cell(buffer, 0, 1).fg)
        assertEquals(21, cell(buffer, 0, 1).bg)
    }

    @Test
    fun `sgr reset restores default style`() {
        val buffer = newBuffer()
        buffer.feed("\u001b[1;31mA\u001b[0mB".toByteArray())
        assertTrue(cell(buffer, 0, 0).bold)
        assertEquals(1, cell(buffer, 0, 0).fg)
        assertFalse(cell(buffer, 0, 1).bold)
        assertEquals(Cell.DEFAULT_FG, cell(buffer, 0, 1).fg)
    }

    @Test
    fun `sgr 39 and 49 restore defaults`() {
        val buffer = newBuffer()
        buffer.feed("\u001b[31;44mA\u001b[39;49mB".toByteArray())
        assertEquals(Cell.DEFAULT_FG, cell(buffer, 0, 1).fg)
        assertEquals(Cell.DEFAULT_BG, cell(buffer, 0, 1).bg)
    }

    // ---- 光标移动 ----

    @Test
    fun `cursor movement commands move and clamp`() {
        val buffer = newBuffer(rows = 3)
        buffer.feed("abcdef".toByteArray()) // cursor at (0,6)
        buffer.feed("\u001b[1D".toByteArray()) // left 1 → (0,5)
        buffer.feed("X".toByteArray())
        assertEquals("abcdeX", screenText(buffer, 0))
        buffer.feed("\u001b[10A".toByteArray()) // up 10 → clamp to row 0
        buffer.feed("\u001b[10D".toByteArray()) // left 10 → clamp to col 0
        buffer.feed("Y".toByteArray())
        assertEquals("YbcdeX", screenText(buffer, 0))
    }

    @Test
    fun `cursor position command is one based`() {
        val buffer = newBuffer()
        buffer.feed("\u001b[2;3HZ".toByteArray())
        assertEquals('Z', cell(buffer, 1, 2).ch)
        // 光标为 0 基：2;3H → (1,2)，写完 Z 后列前进到 3
        assertEquals(1, buffer.cursorScreenRow)
        assertEquals(3, buffer.cursorScreenCol)
    }

    @Test
    fun `cursor column and row commands`() {
        val buffer = newBuffer()
        buffer.feed("\u001b[5GZ".toByteArray())
        assertEquals('Z', cell(buffer, 0, 4).ch)
        buffer.feed("\u001b[3;1H\u001b[3dY".toByteArray())
        assertEquals('Y', cell(buffer, 2, 0).ch)
    }

    // ---- 清除 ----

    @Test
    fun `erase line modes`() {
        val buffer = newBuffer()
        buffer.feed("0123456789".toByteArray())
        buffer.feed("\u001b[2G\u001b[K".toByteArray()) // 光标 col1，清到行尾
        assertEquals("0", screenText(buffer, 0))
    }

    @Test
    fun `erase display mode two clears screen`() {
        val buffer = newBuffer(rows = 3)
        buffer.feed("line1\nline2\nline3".toByteArray())
        buffer.feed("\u001b[2J".toByteArray())
        assertEquals("", screenText(buffer, 0))
        assertEquals("", screenText(buffer, 1))
        assertEquals("", screenText(buffer, 2))
    }

    // ---- 滚动 ----

    @Test
    fun `scroll region restricts cursor movement`() {
        val buffer = newBuffer(rows = 5)
        buffer.feed("\u001b[2;4r\u001b[10A".toByteArray())
        // 光标被限制在滚动区 2..4（1-based 行 2-4 → 索引 1..3）
        assertEquals(1, buffer.cursorScreenRow)
        // 恢复全屏滚动区
        buffer.feed("\u001b[0;0r\u001b[20A".toByteArray())
        assertEquals(0, buffer.cursorScreenRow)
    }

    @Test
    fun `insert and delete lines shift content within region`() {
        val buffer = newBuffer(rows = 4)
        buffer.feed("1\n2\n3\n4".toByteArray())
        buffer.feed("\u001b[2;1H\u001b[1L".toByteArray()) // 行索引1 前插 1 行
        assertEquals("1", screenText(buffer, 0))
        assertEquals("", screenText(buffer, 1))
        assertEquals("2", screenText(buffer, 2))
        buffer.feed("\u001b[1M".toByteArray()) // 删除当前行
        assertEquals("1", screenText(buffer, 0))
        assertEquals("2", screenText(buffer, 1))
        assertEquals("3", screenText(buffer, 2))
    }

    @Test
    fun `insert and delete characters shift within row`() {
        val buffer = newBuffer()
        buffer.feed("abcdef".toByteArray())
        buffer.feed("\u001b[2G\u001b[2P".toByteArray()) // col1 起删 2 字符
        assertEquals("adef", screenText(buffer, 0))
        buffer.feed("\u001b[2G\u001b[1@X".toByteArray()) // col1 插 1 空格后写 X
        assertEquals("aXdef", screenText(buffer, 0))
    }

    @Test
    fun `erase characters blanks cells`() {
        val buffer = newBuffer()
        buffer.feed("abcdef".toByteArray())
        buffer.feed("\u001b[2G\u001b[2X".toByteArray())
        // 从 col1 起擦除 2 格（b、c）→ "a  def"
        assertEquals("a  def", screenText(buffer, 0))
    }

    // ---- 备用屏 ----

    @Test
    fun `alternate screen switches and restores cursor`() {
        val buffer = newBuffer(rows = 4)
        buffer.feed("main".toByteArray())
        buffer.feed("\u001b[?1049h".toByteArray())
        assertTrue(buffer.isUsingAlternateScreen)
        buffer.feed("\u001b[2;1Halt".toByteArray())
        assertEquals("", screenText(buffer, 0))
        assertEquals("alt", screenText(buffer, 1))
        // 退出备用屏：主屏内容与光标恢复（1049 保存/恢复光标 → 回到 (0,4)）
        buffer.feed("\u001b[?1049l".toByteArray())
        assertFalse(buffer.isUsingAlternateScreen)
        assertEquals("main", screenText(buffer, 0))
        assertEquals(4, buffer.cursorScreenCol)
    }

    @Test
    fun `cursor visibility modes`() {
        val buffer = newBuffer()
        assertTrue(buffer.isCursorVisible)
        buffer.feed("\u001b[?25l".toByteArray())
        assertFalse(buffer.isCursorVisible)
        buffer.feed("\u001b[?25h".toByteArray())
        assertTrue(buffer.isCursorVisible)
    }

    // ---- 保存/恢复 ----

    @Test
    fun `save and restore cursor`() {
        val buffer = newBuffer()
        buffer.feed("abc\u001b[s".toByteArray()) // 保存 (0,3)
        buffer.feed("\u001b[3;3H\u001b[uZ".toByteArray()) // 恢复后写 Z
        assertEquals('Z', cell(buffer, 0, 3).ch)
    }

    @Test
    fun `esc 7 and 8 save restore cursor`() {
        val buffer = newBuffer()
        buffer.feed("abc\u001b7".toByteArray())
        buffer.feed("\u001b[3;3H\u001b8Z".toByteArray())
        assertEquals('Z', cell(buffer, 0, 3).ch)
    }

    // ---- 状态报告 ----

    @Test
    fun `device status report answers cursor position`() {
        val buffer = newBuffer()
        var report: String? = null
        buffer.reportOutput = { report = it }
        buffer.feed("\u001b[2;5H\u001b[6n".toByteArray())
        assertEquals("\u001b[2;5R", report)
    }

    // ---- 分片输入 ----

    @Test
    fun `csi sequence split across feeds`() {
        val buffer = newBuffer()
        buffer.feed("\u001b[3".toByteArray())
        buffer.feed("1".toByteArray())
        buffer.feed("m".toByteArray())
        buffer.feed("X".toByteArray())
        assertEquals(1, cell(buffer, 0, 0).fg)
    }

    @Test
    fun `utf8 multibyte split across feeds`() {
        val buffer = newBuffer()
        val bytes = "涂".toByteArray(Charsets.UTF_8)
        buffer.feed(byteArrayOf(bytes[0]))
        buffer.feed(byteArrayOf(bytes[1]))
        buffer.feed(byteArrayOf(bytes[2]))
        assertEquals("涂", screenText(buffer, 0))
    }

    @Test
    fun `osc sequence is ignored`() {
        val buffer = newBuffer()
        buffer.feed("before\u001b]0;title\u0007after".toByteArray())
        assertEquals("beforeafter", screenText(buffer, 0))
    }

    // ---- 换行 ----

    @Test
    fun `linefeed scrolls when cursor at bottom`() {
        val buffer = newBuffer(rows = 3)
        buffer.feed("1\n2\n3\n4".toByteArray())
        // 第 3 行写满后换行触发滚动：首行进入滚动回退
        assertEquals(1, buffer.scrollbackSize())
        assertEquals("2", screenText(buffer, 0))
        assertEquals("3", screenText(buffer, 1))
        assertEquals("4", screenText(buffer, 2))
    }

    @Test
    fun `index and reverse index move cursor`() {
        val buffer = newBuffer(rows = 3)
        buffer.feed("\u001bD".toByteArray()) // index：光标下移
        assertEquals(1, buffer.cursorScreenRow)
        buffer.feed("\u001bM".toByteArray()) // reverse index：光标上移
        assertEquals(0, buffer.cursorScreenRow)
    }
}
