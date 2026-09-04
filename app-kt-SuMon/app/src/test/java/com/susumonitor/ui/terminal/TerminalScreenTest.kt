package com.susumonitor.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalScreenTest {

    @Test
    fun `finger moving down increases history offset`() {
        assertEquals(
            4,
            nextTerminalScrollOffset(
                currentOffset = 1,
                panY = 48f,
                lineHeightPx = 16f,
                maxOffset = 10,
            ),
        )
    }

    @Test
    fun `finger moving up decreases history offset`() {
        assertEquals(
            0,
            nextTerminalScrollOffset(
                currentOffset = 1,
                panY = -48f,
                lineHeightPx = 16f,
                maxOffset = 10,
            ),
        )
    }

    @Test
    fun `history offset stays within available scrollback`() {
        assertEquals(
            10,
            nextTerminalScrollOffset(
                currentOffset = 9,
                panY = 48f,
                lineHeightPx = 16f,
                maxOffset = 10,
            ),
        )
    }
}
