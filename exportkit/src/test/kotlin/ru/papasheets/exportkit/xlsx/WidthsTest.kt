package ru.papasheets.exportkit.xlsx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidthsTest {

    /**
     * Опорные точки калибровки — ширины двух подколонок эталонного журнала
     * (docs/reference/iyun-xlsx). Если они поедут, поедет и вся сверка формата xlsx.
     */
    @Test
    fun `reference dp widths map to the reference excel widths`() {
        assertEquals(7.75, Widths.dpToChars(56), 0.0)
        assertEquals(50.75, Widths.dpToChars(168), 0.0)
    }

    @Test
    fun `width grows monotonically between the reference points`() {
        val between = (56..168 step 8).map { Widths.dpToChars(it) }
        assertEquals(between.sorted(), between)
        assertTrue(between.first() < between.last())
    }

    @Test
    fun `extreme dp values are clamped to a printable range`() {
        assertEquals(4.0, Widths.dpToChars(0), 0.0)
        assertEquals(4.0, Widths.dpToChars(-100), 0.0)
        assertEquals(80.0, Widths.dpToChars(10_000), 0.0)
    }

    @Test
    fun `width is rounded to two decimals so xml stays free of long tails`() {
        val chars = Widths.dpToChars(100)
        assertEquals(chars, Math.round(chars * 100.0) / 100.0, 0.0)
    }
}
