package ru.papasheets.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.data.db.entity.RecordEntity

class GridModelBuilderTest {

    private val day1 = LocalDate.of(2026, 7, 17).toEpochDay()
    private val day2 = LocalDate.of(2026, 7, 18).toEpochDay()

    private fun contractor(id: String, order: Int, archived: Boolean = false) =
        ContractorEntity(id = id, name = "Подрядчик $id", shortName = id, colorIndex = order, orderIndex = order, isArchived = archived, createdAt = 0)

    private fun record(id: String, day: Long, contractorId: String, createdAt: Long) =
        RecordEntity(
            id = id, journalId = "j", dateEpochDay = day, contractorId = contractorId,
            locationCode = "1-01", workText = "работа $id", photoId = null, createdAt = createdAt, updatedAt = createdAt,
        )

    @Test
    fun `unequal record counts per contractor drive row count and cell placement`() {
        val contractors = listOf(contractor("A", 0), contractor("B", 1))
        val records = listOf(
            record("a1", day1, "A", createdAt = 100),
            record("a2", day1, "A", createdAt = 200),
            record("b1", day1, "B", createdAt = 150),
            record("a3", day2, "A", createdAt = 300),
        )

        val model = buildGridModel(records, contractors, sortDesc = false)

        assertEquals(listOf("A", "B"), model.contractors.map { it.id })
        // День 1: max(2,1)=2 строки; день 2: max(1,0)=1 строка.
        assertEquals(3, model.rows.size)

        val d1row0 = model.rows[0]
        assertTrue(d1row0.isFirstOfDay)
        assertEquals(day1, d1row0.dateEpochDay)
        assertEquals("17 июл", d1row0.dayLabel)
        assertEquals("a1", d1row0.cells[0]?.recordId) // раньше по createdAt — выше
        assertEquals("b1", d1row0.cells[1]?.recordId)

        val d1row1 = model.rows[1]
        assertFalse(d1row1.isFirstOfDay)
        assertEquals("a2", d1row1.cells[0]?.recordId)
        assertNull(d1row1.cells[1]) // у B во второй строке дня записи нет

        val d2row0 = model.rows[2]
        assertTrue(d2row0.isFirstOfDay)
        assertEquals(day2, d2row0.dateEpochDay)
        assertEquals("a3", d2row0.cells[0]?.recordId)
        assertNull(d2row0.cells[1])
    }

    @Test
    fun `cell values follow the field order of the model`() {
        val contractors = listOf(contractor("A", 0))
        val records = listOf(record("a1", day1, "A", createdAt = 100))

        val model = buildGridModel(records, contractors, sortDesc = false)

        assertEquals(listOf("location", "work"), model.fields.map { it.id })
        val values = model.rows[0].cells[0]!!.values
        assertEquals("одно значение на поле", model.fields.size, values.size)
        assertEquals("1-01", values[0])
        assertEquals("работа a1", values[1])
    }

    @Test
    fun `missing value yields an empty string instead of shifting positions`() {
        // Пустая локация не должна «съехать» в подколонку вида работ: позиция в values и есть привязка
        // к полю (см. GridCell).
        val contractors = listOf(contractor("A", 0))
        val records = listOf(record("a1", day1, "A", createdAt = 100).copy(locationCode = ""))

        val model = buildGridModel(records, contractors, sortDesc = false)

        val values = model.rows[0].cells[0]!!.values
        assertEquals(model.fields.size, values.size)
        assertEquals("", values[0])
        assertEquals("работа a1", values[1])
    }

    @Test
    fun `sortDesc reverses day order but keeps within-day order`() {
        val contractors = listOf(contractor("A", 0), contractor("B", 1))
        val records = listOf(
            record("a1", day1, "A", createdAt = 100),
            record("a2", day2, "A", createdAt = 200),
        )

        val model = buildGridModel(records, contractors, sortDesc = true)

        assertEquals(day2, model.rows.first().dateEpochDay)
        assertEquals(day1, model.rows.last().dateEpochDay)
    }

    @Test
    fun `archived contractor without records in this journal is excluded`() {
        val contractors = listOf(contractor("A", 0), contractor("B", 1, archived = true))
        val records = listOf(record("a1", day1, "A", createdAt = 100))

        val model = buildGridModel(records, contractors, sortDesc = false)

        assertEquals(listOf("A"), model.contractors.map { it.id })
        assertEquals(1, model.rows[0].cells.size)
    }

    @Test
    fun `archived contractor with records stays as a trailing flagged column`() {
        // B архивирован ПОСЛЕ того, как на нём появились записи в этом журнале — его колонка не должна
        // молча пропасть из уже сведённого месяца (см. GridModelBuilder).
        val contractors = listOf(contractor("A", 0), contractor("B", 1, archived = true), contractor("C", 2))
        val records = listOf(
            record("a1", day1, "A", createdAt = 100),
            record("b1", day1, "B", createdAt = 150),
            record("c1", day1, "C", createdAt = 200),
        )

        val model = buildGridModel(records, contractors, sortDesc = false)

        // Активные (A, C) по orderIndex первыми, архивный с записями (B) — в конце.
        assertEquals(listOf("A", "C", "B"), model.contractors.map { it.id })
        assertFalse(model.contractors[0].isArchived)
        assertFalse(model.contractors[1].isArchived)
        assertTrue(model.contractors[2].isArchived)

        val row = model.rows[0]
        assertEquals("a1", row.cells[0]?.recordId)
        assertEquals("c1", row.cells[1]?.recordId)
        assertEquals("b1", row.cells[2]?.recordId)
    }
}
