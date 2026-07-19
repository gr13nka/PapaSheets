package ru.papasheets.exportkit.xlsx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.papasheets.exportkit.model.SnapshotCell
import ru.papasheets.exportkit.model.SnapshotField
import ru.papasheets.exportkit.model.SnapshotRow

class RowHeightTest {

    private val narrowWrap = SnapshotField(title = "Текст", widthChars = 10.0, wrap = true)
    private val noWrap = SnapshotField(title = "Л", widthChars = 5.0, wrap = false)

    private fun row(vararg values: List<String>?) =
        SnapshotRow(values.map { it?.let { v -> SnapshotCell(v, photoId = null) } })

    @Test
    fun `explicit newlines each start a new line`() {
        val fields = listOf(narrowWrap)
        assertEquals(3, RowHeight.estimateTextLines(row(listOf("а\nб\nв")), fields))
    }

    @Test
    fun `long text wraps by column capacity`() {
        val fields = listOf(narrowWrap)
        // 25 символов при вместимости 10 → 3 строки.
        assertEquals(3, RowHeight.estimateTextLines(row(listOf("x".repeat(25))), fields))
    }

    @Test
    fun `wrapping and explicit newlines combine`() {
        val fields = listOf(narrowWrap)
        assertEquals(4, RowHeight.estimateTextLines(row(listOf("x".repeat(25) + "\nхвост")), fields))
    }

    @Test
    fun `empty values and empty slots stay one line`() {
        val fields = listOf(narrowWrap)
        assertEquals(1, RowHeight.estimateTextLines(row(listOf("")), fields))
        assertEquals(1, RowHeight.estimateTextLines(row(null, null), fields))
    }

    @Test
    fun `fields without wrap never add lines`() {
        assertEquals(1, RowHeight.estimateTextLines(row(listOf("x".repeat(100))), listOf(noWrap)))
        assertEquals(1, RowHeight.estimateTextLines(row(listOf("что угодно")), emptyList()))
    }

    @Test
    fun `the tallest cell of the row wins`() {
        val fields = listOf(narrowWrap)
        assertEquals(3, RowHeight.estimateTextLines(row(listOf("коротко"), listOf("а\nб\nв")), fields))
    }

    /**
     * Excel переносит по словам, и слово, не влезшее в остаток строки, уезжает на новую целиком.
     * Деление длины на вместимость дало бы здесь 2 строки (18 символов при вместимости 10), текст
     * занял бы 3, и хвост оказался бы срезан при печати.
     */
    @Test
    fun `a word that does not fit moves to the next line whole`() {
        val fields = listOf(narrowWrap)
        assertEquals(3, RowHeight.estimateTextLines(row(listOf("кладка кирпич раствор")), fields))
    }

    /** Слово длиннее всей колонки Excel всё-таки рвёт — единственный случай посимвольного переноса. */
    @Test
    fun `a word longer than the column is broken across lines`() {
        val fields = listOf(narrowWrap)
        assertEquals(3, RowHeight.estimateTextLines(row(listOf("x".repeat(21))), fields))
    }

    /** Пробелы между словами не должны сами по себе плодить строки. */
    @Test
    fun `short words share a line up to the column capacity`() {
        val fields = listOf(narrowWrap)
        assertEquals(1, RowHeight.estimateTextLines(row(listOf("а б в г д")), fields))
    }

    /**
     * 409.5pt — потолок высоты строки в OOXML: выше Excel отказывается открывать файл целиком.
     * Простыня текста в ячейке не должна стоить прорабу всего экспорта.
     */
    @Test
    fun `a photo row never exceeds the OOXML row height ceiling`() {
        val fields = listOf(narrowWrap)
        val giant = row(listOf(List(500) { "строка $it" }.joinToString("\n")))

        assertEquals(MAX_ROW_HEIGHT_PT, RowHeight.forPhotoRow(giant, fields), 0.0)
    }

    @Test
    fun `a photo row is never shorter than the photo itself`() {
        val fields = listOf(narrowWrap)
        assertEquals(PHOTO_ROW_HEIGHT_PT, RowHeight.forPhotoRow(row(listOf("коротко")), fields), 0.0)

        // Текста больше, чем помещается в высоту фото → строка растёт под текст.
        val tall = RowHeight.forPhotoRow(row(listOf(List(10) { "строка $it" }.joinToString("\n"))), fields)
        assertEquals(10 * LINE_HEIGHT_PT, tall, 0.0)
        assertTrue(tall > PHOTO_ROW_HEIGHT_PT)
    }
}
