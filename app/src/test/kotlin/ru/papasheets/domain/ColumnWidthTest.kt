package ru.papasheets.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ColumnWidthTest {

    @Test
    fun `exact preset values map to themselves`() {
        assertEquals(ColumnWidth.NARROW, ColumnWidth.nearest(56))
        assertEquals(ColumnWidth.MEDIUM, ColumnWidth.nearest(112))
        assertEquals(ColumnWidth.WIDE, ColumnWidth.nearest(168))
        assertEquals(ColumnWidth.EXTRA_WIDE, ColumnWidth.nearest(224))
    }

    /** Ровно посередине между пресетами побеждает более широкий — тексту в ячейке это выгоднее. */
    @Test
    fun `a tie between two presets resolves to the wider one`() {
        assertEquals(ColumnWidth.MEDIUM, ColumnWidth.nearest((56 + 112) / 2)) // 84
        assertEquals(ColumnWidth.WIDE, ColumnWidth.nearest((112 + 168) / 2)) // 140
        assertEquals(ColumnWidth.EXTRA_WIDE, ColumnWidth.nearest((168 + 224) / 2)) // 196
    }

    @Test
    fun `values just off a midpoint pick the strictly closer preset`() {
        assertEquals(ColumnWidth.NARROW, ColumnWidth.nearest(83))
        assertEquals(ColumnWidth.MEDIUM, ColumnWidth.nearest(85))
    }

    @Test
    fun `extreme values clamp to the nearest end preset`() {
        assertEquals(ColumnWidth.NARROW, ColumnWidth.nearest(24))
        assertEquals(ColumnWidth.EXTRA_WIDE, ColumnWidth.nearest(400))
    }

    @Test
    fun `default preset is medium`() {
        assertEquals(ColumnWidth.MEDIUM, ColumnWidth.DEFAULT)
    }
}
