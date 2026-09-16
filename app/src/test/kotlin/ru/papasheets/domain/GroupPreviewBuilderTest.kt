package ru.papasheets.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.papasheets.matrixgrid.ContractorColumn
import ru.papasheets.testing.testField

class GroupPreviewBuilderTest {

    private val firstDay = LocalDate.of(2026, 7, 17)
    private val group = ContractorColumn("g", "Группа А", "ГА", 2)

    private fun label(date: LocalDate) = "${date.dayOfMonth}.${date.monthValue}"

    @Test
    fun `three rows cover two days with the right first-of-day flags`() {
        val model = buildGroupPreview(listOf(testField("volume")), group, emptyMap(), emptyMap(), firstDay, ::label)

        assertEquals(3, model.rows.size)
        val (row0, row1, row2) = model.rows
        assertEquals(firstDay.toEpochDay(), row0.dateEpochDay)
        assertTrue(row0.isFirstOfDay)
        assertEquals(firstDay.toEpochDay(), row1.dateEpochDay)
        assertFalse(row1.isFirstOfDay)
        assertEquals(firstDay.plusDays(1).toEpochDay(), row2.dateEpochDay)
        assertTrue(row2.isFirstOfDay)
    }

    @Test
    fun `only the first row carries a photo placeholder`() {
        val model = buildGroupPreview(listOf(testField("volume")), group, emptyMap(), emptyMap(), firstDay, ::label)

        assertEquals(1, model.rows[0].cells[0]!!.thumbKeys.size)
        assertTrue(model.rows[1].cells[0]!!.thumbKeys.isEmpty())
        assertTrue(model.rows[2].cells[0]!!.thumbKeys.isEmpty())
    }

    @Test
    fun `the single column carries the given group`() {
        val model = buildGroupPreview(listOf(testField("volume")), group, emptyMap(), emptyMap(), firstDay, ::label)

        assertEquals(listOf("g"), model.contractors.map { it.id })
        model.rows.forEach { assertEquals(1, it.cells.size) }
    }

    @Test
    fun `samples cycle by row index and repeat once exhausted`() {
        val samples = mapOf("volume" to listOf("12 м²", "8 м²"))
        val model = buildGroupPreview(listOf(testField("volume")), group, samples, emptyMap(), firstDay, ::label)

        assertEquals("12 м²", model.rows[0].cells[0]!!.values[0])
        assertEquals("8 м²", model.rows[1].cells[0]!!.values[0])
        assertEquals("12 м²", model.rows[2].cells[0]!!.values[0]) // 2 % 2 == 0 — снова первый пример
    }

    @Test
    fun `a field with no samples falls back to its own label`() {
        val model = buildGroupPreview(
            listOf(testField("note", label = "Замечание")), group, emptyMap(), emptyMap(), firstDay, ::label,
        )

        model.rows.forEach { row -> assertEquals("Замечание", row.cells[0]!!.values[0]) }
    }

    @Test
    fun `an empty sample list also falls back to the label, not a blank cell`() {
        val model = buildGroupPreview(
            listOf(testField("note", label = "Замечание")),
            group,
            mapOf("note" to emptyList()),
            emptyMap(),
            firstDay,
            ::label,
        )

        assertEquals("Замечание", model.rows[0].cells[0]!!.values[0])
    }

    @Test
    fun `value colors follow the sample actually shown in that row`() {
        val samples = mapOf("volume" to listOf("12 м²", "8 м²"))
        val colors = mapOf("volume" to mapOf("12 м²" to 3, "8 м²" to 5))
        val model = buildGroupPreview(listOf(testField("volume")), group, samples, colors, firstDay, ::label)

        assertEquals(3, model.rows[0].cells[0]!!.valueColors[0])
        assertEquals(5, model.rows[1].cells[0]!!.valueColors[0])
        assertEquals(3, model.rows[2].cells[0]!!.valueColors[0])
    }

    /** Превью не должно уметь показать колонку иначе, чем настоящая матрица — источник ровно один. */
    @Test
    fun `field mapping matches buildGridModel for the same field list`() {
        val fields = listOf(
            testField("volume", title = "ОБЪЁМ", columnWidthDp = 72, maxLines = 0, showAtCompactLod = false),
            testField("note", title = "ЗАМЕЧАНИЕ", columnWidthDp = 120),
        )

        val preview = buildGroupPreview(fields, group, emptyMap(), emptyMap(), firstDay, ::label)
        val fromGrid = buildGridModel(records = emptyList(), contractors = emptyList(), fields = fields, valueColors = emptyMap())

        assertEquals(fromGrid.fields.map { it.id }, preview.fields.map { it.id })
        assertEquals(fromGrid.fields.map { it.title }, preview.fields.map { it.title })
        assertEquals(fromGrid.fields.map { it.widthDp }, preview.fields.map { it.widthDp })
        assertEquals(fromGrid.fields.map { it.maxLines }, preview.fields.map { it.maxLines })
        assertEquals(fromGrid.fields.map { it.showAtCompactLod }, preview.fields.map { it.showAtCompactLod })
    }
}
