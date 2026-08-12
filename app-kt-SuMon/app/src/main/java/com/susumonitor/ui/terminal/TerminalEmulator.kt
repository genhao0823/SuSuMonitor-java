package com.susumonitor.ui.terminal

/**
 * 终端单格：字符 + 调色板索引样式。
 *
 * [fg]/[bg] 为 xterm 256 色索引，`-1` 表示默认值（渲染层使用主题底色）。
 */
data class Cell(
    val ch: Char = ' ',
    val fg: Int = DEFAULT_FG,
    val bg: Int = DEFAULT_BG,
    val bold: Boolean = false,
) {
    companion object {
        /** 默认前景：调色板 7（白）。 */
        const val DEFAULT_FG = 7

        /** 默认背景：无，渲染层使用终端底色。 */
        const val DEFAULT_BG = -1

        /** 空白格。 */
        val EMPTY = Cell()
    }
}

/** xterm 256 色映射表：16 基础色 + 6×6×6 立方体 + 24 级灰阶。 */
object TerminalPalette {

    private val base16 = intArrayOf(
        0xFF000000.toInt(), 0xFFCD0000.toInt(), 0xFF00CD00.toInt(), 0xFFCDCD00.toInt(),
        0xFF0000EE.toInt(), 0xFFCD00CD.toInt(), 0xFF00CDCD.toInt(), 0xFFE5E5E5.toInt(),
        0xFF7F7F7F.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(),
        0xFF5C5CFF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt(),
    )

    private val cubeLevels = intArrayOf(0x00, 0x5F, 0x87, 0xAF, 0xD7, 0xFF)

    private val cube = IntArray(216) { index ->
        val r = cubeLevels[index / 36]
        val g = cubeLevels[(index / 6) % 6]
        val b = cubeLevels[index % 6]
        0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    private val grays = IntArray(24) { index ->
        val v = 8 + index * 10
        0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
    }

    /** 将 256 色索引映射为 ARGB 颜色。 */
    fun color(index: Int): Int = when (index) {
        in 0..15 -> base16[index]
        in 16..231 -> cube[index - 16]
        in 232..255 -> grays[index - 232]
        else -> base16[7]
    }
}

/** 固定网格屏幕：rows×cols 的 Cell 矩阵。 */
internal class Screen(rows: Int, cols: Int) {
    var rows: Int = rows
        private set
    var cols: Int = cols
        private set
    var grid: Array<Array<Cell>> = Array(rows) { Array(cols) { Cell() } }
        private set

    fun clear() {
        for (row in grid) row.fill(Cell())
    }

    /** 整行填充同一格样式（清行/滚动补空行使用）。 */
    fun fillRow(row: Int, cell: Cell) {
        grid[row].fill(cell)
    }

    /** 按新尺寸重建网格，保留左上角旧内容（终端 resize 近似行为）。 */
    fun resizeGrid(newRows: Int, newCols: Int, old: Screen, oldCols: Int) {
        rows = newRows
        cols = newCols
        val newGrid = Array(newRows) { Array(newCols) { Cell() } }
        val copyRows = minOf(old.grid.size, newRows)
        for (r in 0 until copyRows) {
            val src = old.grid[r]
            val dst = newGrid[r]
            val copyCols = minOf(oldCols, newCols)
            for (c in 0 until copyCols) dst[c] = src[c]
        }
        grid = newGrid
    }
}

/**
 * 自研 ANSI 终端模拟器：增量字节流解析 + 主/备用双屏缓冲 + 滚动回退。
 *
 * 支持子集（对应 top/htop 类 TUI 需求）：
 *  - CSI：`H/f` 定位、`A/B/C/D` 移动、`G` 列、`d` 行、`J/K` 清除、`m` SGR
 *    （0/1/22、30-37/90-97 前景、40-47/100-107 背景、38;5;n/48;5;n 256 色、39/49 默认）、
 *    `h/l` 模式（?1049/?47 备用屏、?25 光标、?7 自动换行）、`s/u` 保存恢复光标、
 *    `r` 滚动区、`S/T` 滚动、`n` 状态报告（应答 `ESC[row;colR`）、
 *    `X/@/P` 擦除/插入/删除字符、`L/M` 插入/删除行
 *  - 单字符控制：CR/LF/BS/TAB；ESC 7/8 保存恢复光标、ESC D/M 索引/反向索引
 *  - OSC 忽略至 BEL/ST
 *
 * 边界外：vi 全指令集、DEC 私有光标样式、OSC 超链接、真彩色（38;2 忽略）。
 */
class TerminalBuffer(
    initialCols: Int = 80,
    initialRows: Int = 24,
    val scrollbackLimit: Int = 2000,
) {

    private var cols: Int = initialCols.coerceAtLeast(2)
    private var rows: Int = initialRows.coerceAtLeast(1)
    private val primary = Screen(rows, cols)
    private val alternate = Screen(rows, cols)
    private val scrollback = ArrayDeque<Array<Cell>>()

    private var useAlternate = false
    private var cursorRow = 0
    private var cursorCol = 0
    private var cursorVisible = true
    private var savedCursorRow = 0
    private var savedCursorCol = 0
    private var savedCursorVisible = true
    private var wrapPending = false
    private var scrollRegionTop = 0
    private var scrollRegionBottom = rows - 1

    private var fg = Cell.DEFAULT_FG
    private var bg = Cell.DEFAULT_BG
    private var bold = false

    private enum class ParseState { TEXT, ESC, CSI, OSC }

    private var parseState = ParseState.TEXT
    private val csiParams = mutableListOf<Int>()
    private var csiPrivate = false
    private val oscBuffer = StringBuilder()
    private var utf8Remaining = 0
    private var utf8CodePoint = 0

    /**
     * 状态报告（CSI n）应答出口；由 UI 层接到 PTY 输入通道回传。
     * 应答形如 `ESC[row;colR`。
     */
    @Volatile
    var reportOutput: ((String) -> Unit)? = null

    val screenRows: Int get() = rows
    val screenCols: Int get() = cols
    val cursorScreenRow: Int get() = cursorRow
    val cursorScreenCol: Int get() = cursorCol
    val isCursorVisible: Boolean get() = cursorVisible
    val isUsingAlternateScreen: Boolean get() = useAlternate

    /** 清空全部缓冲（重连/重开时使用）。 */
    @Synchronized
    fun clear() {
        primary.clear()
        alternate.clear()
        scrollback.clear()
        cursorRow = 0
        cursorCol = 0
        wrapPending = false
        fg = Cell.DEFAULT_FG
        bg = Cell.DEFAULT_BG
        bold = false
    }

    /** 调整终端尺寸：重建双屏网格并保留左上角内容，滚动回退行右侧截断。 */
    @Synchronized
    fun resize(newCols: Int, newRows: Int) {
        val nc = newCols.coerceAtLeast(2)
        val nr = newRows.coerceAtLeast(1)
        if (nc == cols && nr == rows) return
        val oldCols = cols
        primary.resizeGrid(nr, nc, primary, oldCols)
        alternate.resizeGrid(nr, nc, alternate, oldCols)
        cols = nc
        rows = nr
        scrollRegionTop = 0
        scrollRegionBottom = nr - 1
        cursorRow = cursorRow.coerceIn(0, nr - 1)
        cursorCol = cursorCol.coerceIn(0, nc - 1)
        savedCursorRow = savedCursorRow.coerceIn(0, nr - 1)
        savedCursorCol = savedCursorCol.coerceIn(0, nc - 1)
        wrapPending = false
    }

    /** 增量喂入 PTY 输出字节（UTF-8 多字节与转义序列可跨调用分片）。 */
    @Synchronized
    fun feed(bytes: ByteArray) {
        for (byte in bytes) feedByte(byte.toInt() and 0xFF)
    }

    /**
     * 可视区快照：滚动回退 + 当前屏（备用屏启用时显示备用屏），
     * 行统一按当前 cols 右侧截断，供渲染层绘制。
     */
    @Synchronized
    fun visibleRows(): List<List<Cell>> {
        val screen = activeScreen()
        val result = ArrayList<List<Cell>>(scrollback.size + rows)
        for (row in scrollback) result.add(truncated(row))
        for (row in screen.grid) result.add(truncated(row))
        return result
    }

    /** 滚动回退行数（供上层显示滚动区信息）。 */
    @Synchronized
    fun scrollbackSize(): Int = scrollback.size

    private fun truncated(row: Array<Cell>): List<Cell> =
        if (row.size <= cols) row.toList() else row.take(cols)

    private fun activeScreen(): Screen = if (useAlternate) alternate else primary

    private fun feedByte(byte: Int) {
        when (parseState) {
            ParseState.TEXT -> onTextByte(byte)
            ParseState.ESC -> onEscByte(byte)
            ParseState.CSI -> onCsiByte(byte)
            ParseState.OSC -> onOscByte(byte)
        }
    }

    // ---- 文本态 ----

    private fun onTextByte(byte: Int) {
        if (byte == 0x1B) {
            parseState = ParseState.ESC
        } else if (byte in 0xC0..0xFF) {
            // UTF-8 多字节序列：按首字节推断续字节数
            utf8CodePoint = when {
                byte >= 0xF0 -> byte and 0x07
                byte >= 0xE0 -> byte and 0x0F
                else -> byte and 0x1F
            }
            utf8Remaining = when {
                byte >= 0xF0 -> 3
                byte >= 0xE0 -> 2
                else -> 1
            }
            if (utf8Remaining == 0) {
                putChar(byte.toChar())
            }
        } else if (utf8Remaining > 0) {
            utf8CodePoint = (utf8CodePoint shl 6) or (byte and 0x3F)
            utf8Remaining--
            if (utf8Remaining == 0) {
                putChar(utf8CodePoint.toChar())
            }
        } else {
            when (byte) {
                0x0D -> cursorCol = 0
                0x0A -> {
                    // 兼容裸 \n（隐式 CR）：PTY onlcr 下常规为 \r\n，但部分程序只发 \n
                    cursorCol = 0
                    newline()
                }
                0x08 -> if (cursorCol > 0) cursorCol--
                0x09 -> {
                    val target = ((cursorCol / 8) + 1) * 8
                    cursorCol = target.coerceAtMost(cols - 1)
                }
                else -> if (byte >= 0x20) putChar(byte.toChar())
            }
        }
    }

    // ---- 转义态 ----

    private fun onEscByte(byte: Int) {
        parseState = ParseState.TEXT
        when (byte) {
            '['.code -> {
                parseState = ParseState.CSI
                csiParams.clear()
                csiPrivate = false
            }
            ']'.code -> {
                parseState = ParseState.OSC
                oscBuffer.clear()
            }
            '7'.code -> saveCursor()
            '8'.code -> restoreCursor()
            'D'.code -> index()
            'M'.code -> reverseIndex()
            else -> Unit // 其余 ESC 序列忽略
        }
    }

    // ---- CSI 态 ----

    private fun onCsiByte(byte: Int) {
        when {
            byte in '0'.code..'9'.code -> {
                // 连续数字追加到当前参数；紧跟分隔符（-1）时先开新槽
                if (csiParams.isEmpty() || csiParams.last() == -1) csiParams.add(0)
                val last = csiParams.size - 1
                csiParams[last] = csiParams[last] * 10 + (byte - '0'.code)
            }
            byte == ';'.code -> csiParams.add(-1)
            byte == '?'.code || byte == '>'.code -> csiPrivate = byte == '?'.code
            byte in 0x40..0x7E -> {
                parseState = ParseState.TEXT
                // 移除分隔符占位（-1），避免空参数被当作 0 参与 dispatch
                csiParams.removeAll { it == -1 }
                dispatchCsi(byte.toChar())
            }
            else -> Unit // 中间字节（空格/冒号等）忽略
        }
    }

    private fun param(index: Int, default: Int): Int {
        val value = if (index < csiParams.size) csiParams[index] else -1
        return if (value < 0) default else value
    }

    private fun dispatchCsi(final: Char) {
        when (final) {
            'A' -> moveCursor(-param(0, 1), 0)
            'B' -> moveCursor(param(0, 1), 0)
            'C' -> moveCursor(0, param(0, 1))
            'D' -> moveCursor(0, -param(0, 1))
            'H', 'f' -> {
                val row = (param(0, 1).takeIf { it > 0 } ?: 1) - 1
                val col = (param(1, 1).takeIf { it > 0 } ?: 1) - 1
                setCursor(row, col)
            }
            'G' -> cursorCol = (param(0, 1).takeIf { it > 0 } ?: 1).minus(1).coerceIn(0, cols - 1)
            'd' -> cursorRow = (param(0, 1).takeIf { it > 0 } ?: 1).minus(1).coerceIn(0, rows - 1)
            'J' -> eraseDisplay(param(0, 0))
            'K' -> eraseLine(param(0, 0))
            'm' -> applySgr()
            'h' -> if (csiPrivate) applyPrivateMode(true, param(0, 0))
            'l' -> if (csiPrivate) applyPrivateMode(false, param(0, 0))
            's' -> saveCursor()
            'u' -> restoreCursor()
            'r' -> setScrollRegion()
            'S' -> scrollRegionUp(param(0, 1))
            'T' -> scrollRegionDown(param(0, 1))
            'n' -> deviceStatusReport(param(0, 0))
            'X' -> eraseChars(param(0, 1))
            '@' -> insertChars(param(0, 1))
            'P' -> deleteChars(param(0, 1))
            'L' -> insertLines(param(0, 1))
            'M' -> deleteLines(param(0, 1))
            else -> Unit
        }
        wrapPending = false
    }

    // ---- OSC 态 ----

    private fun onOscByte(byte: Int) {
        if (byte == 0x07 || byte == 0x1B) {
            parseState = ParseState.TEXT
            oscBuffer.clear()
        }
    }

    // ---- 缓冲操作 ----

    private fun putChar(c: Char) {
        if (wrapPending) {
            wrapPending = false
            cursorCol = 0
            newline()
        }
        activeScreen().grid[cursorRow][cursorCol] = Cell(c, fg, bg, bold)
        if (cursorCol == cols - 1) {
            wrapPending = true
        } else {
            cursorCol++
        }
    }

    private fun newline() {
        if (cursorRow == scrollRegionBottom) {
            scrollRegionUp(1)
        } else {
            cursorRow++
        }
    }

    private fun index() {
        if (cursorRow == scrollRegionBottom) scrollRegionUp(1) else cursorRow++
    }

    private fun reverseIndex() {
        if (cursorRow == scrollRegionTop) scrollRegionDown(1) else cursorRow--
    }

    private fun moveCursor(dRow: Int, dCol: Int) {
        cursorRow = (cursorRow + dRow).coerceIn(scrollRegionTop, scrollRegionBottom)
        cursorCol = (cursorCol + dCol).coerceIn(0, cols - 1)
    }

    private fun setCursor(row: Int, col: Int) {
        cursorRow = row.coerceIn(scrollRegionTop, scrollRegionBottom)
        cursorCol = col.coerceIn(0, cols - 1)
    }

    private fun saveCursor() {
        savedCursorRow = cursorRow
        savedCursorCol = cursorCol
        savedCursorVisible = cursorVisible
    }

    private fun restoreCursor() {
        cursorRow = savedCursorRow.coerceIn(0, rows - 1)
        cursorCol = savedCursorCol.coerceIn(0, cols - 1)
        cursorVisible = savedCursorVisible
    }

    /** 滚动区内上移 count 行；满屏滚动且非备用屏时移除行进入滚动回退。 */
    private fun scrollRegionUp(count: Int) {
        val amount = count.coerceAtLeast(1)
        val screen = activeScreen()
        val top = scrollRegionTop
        val bottom = scrollRegionBottom
        val regionHeight = bottom - top + 1
        val effective = amount.coerceAtMost(regionHeight)
        for (i in 0 until effective) {
            if (top == 0 && bottom == rows - 1 && !useAlternate) {
                pushScrollback(screen.grid[0])
            }
            // 逐行上移引用；底部行换全新空白数组（fill 会误清与上一行共享的数组）
            for (r in top until bottom) {
                screen.grid[r] = screen.grid[r + 1]
            }
            screen.grid[bottom] = Array(cols) { Cell() }
        }
        wrapPending = false
    }

    private fun scrollRegionDown(count: Int) {
        val amount = count.coerceAtLeast(1)
        val screen = activeScreen()
        val top = scrollRegionTop
        val bottom = scrollRegionBottom
        val regionHeight = bottom - top + 1
        val effective = amount.coerceAtMost(regionHeight)
        repeat(effective) {
            for (r in bottom downTo top + 1) {
                screen.grid[r] = screen.grid[r - 1]
            }
            screen.grid[top] = Array(cols) { Cell() }
        }
        wrapPending = false
    }

    private fun pushScrollback(row: Array<Cell>) {
        scrollback.addLast(row.copyOf())
        while (scrollback.size > scrollbackLimit) {
            scrollback.removeFirst()
        }
    }

    private fun eraseDisplay(mode: Int) {
        val screen = activeScreen()
        when (mode) {
            0 -> {
                eraseLine(0)
                for (r in cursorRow + 1 until rows) screen.fillRow(r, Cell())
            }
            1 -> {
                eraseLine(1)
                for (r in 0 until cursorRow) screen.fillRow(r, Cell())
            }
            2, 3 -> {
                screen.clear()
                if (mode == 3) scrollback.clear()
            }
            else -> Unit
        }
    }

    private fun eraseLine(mode: Int) {
        val screen = activeScreen()
        val row = screen.grid[cursorRow]
        when (mode) {
            0 -> for (c in cursorCol until cols) row[c] = Cell()
            1 -> for (c in 0..cursorCol) row[c] = Cell()
            2 -> row.fill(Cell())
            else -> Unit
        }
    }

    private fun applySgr() {
        if (csiParams.isEmpty()) csiParams.add(0)
        var i = 0
        while (i < csiParams.size) {
            val value = if (csiParams[i] < 0) 0 else csiParams[i]
            when (value) {
                0 -> {
                    fg = Cell.DEFAULT_FG
                    bg = Cell.DEFAULT_BG
                    bold = false
                }
                1 -> bold = true
                22 -> bold = false
                in 30..37 -> fg = value - 30
                in 40..47 -> bg = value - 40
                in 90..97 -> fg = 8 + value - 90
                in 100..107 -> bg = 8 + value - 100
                39 -> fg = Cell.DEFAULT_FG
                49 -> bg = Cell.DEFAULT_BG
                38, 48 -> {
                    // 扩展色：38;5;n / 48;5;n（256 色）；38;2;r;g;b 真彩色忽略
                    if (i + 1 < csiParams.size && csiParams[i + 1] == 5 && i + 2 < csiParams.size) {
                        val colorIndex = csiParams[i + 2].coerceIn(0, 255)
                        if (value == 38) fg = colorIndex else bg = colorIndex
                        i += 2
                    } else if (i + 1 < csiParams.size && csiParams[i + 1] == 2) {
                        i += 4 // 跳过 2;r;g;b
                    }
                }
                else -> Unit
            }
            i++
        }
    }

    private fun applyPrivateMode(enable: Boolean, mode: Int) {
        when (mode) {
            1049 -> {
                if (enable) {
                    saveCursor()
                    useAlternate = true
                    alternate.clear()
                    cursorRow = 0
                    cursorCol = 0
                } else {
                    useAlternate = false
                    restoreCursor()
                }
            }
            47 -> useAlternate = enable
            25 -> cursorVisible = enable
            7 -> Unit // 自动换行恒启用
            else -> Unit
        }
        wrapPending = false
    }

    private fun setScrollRegion() {
        val top = (param(0, 1).takeIf { it > 0 } ?: 1) - 1
        val bottom = (param(1, rows).takeIf { it > 0 } ?: rows) - 1
        if (bottom > top) {
            scrollRegionTop = top.coerceIn(0, rows - 1)
            scrollRegionBottom = bottom.coerceIn(0, rows - 1)
            cursorRow = 0
            cursorCol = 0
        }
    }

    private fun deviceStatusReport(request: Int) {
        val response = when (request) {
            5 -> "\u001b[0n"
            6 -> "\u001b[${cursorRow + 1};${cursorCol + 1}R"
            else -> return
        }
        reportOutput?.invoke(response)
    }

    private fun eraseChars(count: Int) {
        val row = activeScreen().grid[cursorRow]
        val amount = count.coerceAtLeast(1)
        for (c in cursorCol until minOf(cursorCol + amount, cols)) row[c] = Cell()
    }

    private fun insertChars(count: Int) {
        val row = activeScreen().grid[cursorRow]
        val amount = count.coerceAtLeast(1)
        for (c in cols - 1 downTo cursorCol + 1) {
            row[c] = if (c - amount >= cursorCol) row[c - amount] else Cell()
        }
        for (c in cursorCol until minOf(cursorCol + amount, cols)) row[c] = Cell()
    }

    private fun deleteChars(count: Int) {
        val row = activeScreen().grid[cursorRow]
        val amount = count.coerceAtLeast(1)
        for (c in cursorCol until cols - amount) {
            row[c] = row[c + amount]
        }
        for (c in maxOf(cursorCol, cols - amount) until cols) row[c] = Cell()
    }

    private fun insertLines(count: Int) {
        val screen = activeScreen()
        val amount = count.coerceAtLeast(1)
        val top = scrollRegionTop
        val bottom = scrollRegionBottom
        if (cursorRow !in top..bottom) return
        repeat(amount.coerceAtMost(bottom - cursorRow + 1)) {
            for (r in bottom downTo cursorRow + 1) {
                screen.grid[r] = screen.grid[r - 1]
            }
            // 插入行换全新空白数组（fill 会误清与下一行共享的数组）
            screen.grid[cursorRow] = Array(cols) { Cell() }
        }
        wrapPending = false
    }

    private fun deleteLines(count: Int) {
        val screen = activeScreen()
        val amount = count.coerceAtLeast(1)
        val top = scrollRegionTop
        val bottom = scrollRegionBottom
        if (cursorRow !in top..bottom) return
        repeat(amount.coerceAtMost(bottom - cursorRow + 1)) {
            for (r in cursorRow until bottom) {
                screen.grid[r] = screen.grid[r + 1]
            }
            screen.grid[bottom] = Array(cols) { Cell() }
        }
        wrapPending = false
    }
}
