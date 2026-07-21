package ru.papasheets.domain.export

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.exportkit.backup.BuiltInFields
import ru.papasheets.testing.builtInFields
import ru.papasheets.testing.testField
import ru.papasheets.testing.testRecord

class SnapshotBuilderTest {

    private val day1 = LocalDate.of(2026, 7, 17).toEpochDay()
    private val day2 = LocalDate.of(2026, 7, 18).toEpochDay()

    private fun contractor(id: String, order: Int, archived: Boolean = false) =
        ContractorEntity(id = id, name = "Подрядчик $id", shortName = id, colorIndex = order, orderIndex = order, isArchived = archived, createdAt = 0)

    private fun record(
        id: String,
        day: Long,
        contractorId: String,
        createdAt: Long,
        photoId: String? = null,
        photoId2: String? = null,
    ) = testRecord(
        id = id, dateEpochDay = day, contractorId = contractorId,
        photoId = photoId, photoId2 = photoId2, createdAt = createdAt,
        values = mapOf(BuiltInFields.LOCATION_ID to "1-01", BuiltInFields.WORK_ID to "работа $id"),
    )

    @Test
    fun `snapshot sorts days ascending regardless of createdAt order and maps grid cells`() {
        val contractors = listOf(contractor("A", 0), contractor("B", 1))
        val records = listOf(
            record("b1", day2, "B", createdAt = 100, photoId = "p1", photoId2 = "p2"),
            record("a1", day1, "A", createdAt = 200),
        )

        val snapshot = buildJournalSnapshot("Июль 2026", records, contractors, builtInFields)

        assertEquals("Июль 2026", snapshot.title)
        assertEquals(listOf("Подрядчик A", "Подрядчик B"), snapshot.contractors.map { it.name })
        // Без года: он уже стоит в заголовке журнала, который идёт в имя листа и имя файла.
        assertEquals(listOf("17.07", "18.07"), snapshot.days.map { it.dateLabel })

        val day1Row = snapshot.days[0].rows.single()
        // Порядок значений ячейки строго соответствует snapshot.fields.
        assertEquals(listOf("1-01", "работа a1"), day1Row.cells[0]?.values)
        assertEquals(emptyList<String>(), day1Row.cells[0]?.photoIds)
        assertNull(day1Row.cells[1])

        val day2Row = snapshot.days[1].rows.single()
        assertNull(day2Row.cells[0])
        // Оба фото записи доходят до снимка в порядке слотов.
        assertEquals(listOf("p1", "p2"), day2Row.cells[1]?.photoIds)
    }

    @Test
    fun `archived contractor with records stays as a trailing column`() {
        val contractors = listOf(contractor("A", 0), contractor("Z", 1, archived = true))
        val records = listOf(record("z1", day1, "Z", createdAt = 100))

        val snapshot = buildJournalSnapshot("Июль 2026", records, contractors, builtInFields)

        assertEquals(listOf("Подрядчик A", "Подрядчик Z"), snapshot.contractors.map { it.name })
        assertEquals(listOf("1-01", "работа z1"), snapshot.days.single().rows.single().cells[1]?.values)
    }

    /**
     * Поля снимка описывают ту же раскладку, что и матрица: ширина переведена из dp в единицы Excel,
     * перенос строк включён там, где матрица снимает потолок строк.
     */
    @Test
    fun `fields carry excel geometry derived from the grid model`() {
        val snapshot = buildJournalSnapshot(
            "Июль 2026",
            listOf(record("a1", day1, "A", createdAt = 0)),
            listOf(contractor("A", 0)),
            builtInFields,
        )

        assertEquals(listOf("Л", "ВИД РАБОТ"), snapshot.fields.map { it.title })
        assertEquals(listOf(7.75, 50.75), snapshot.fields.map { it.widthChars })
        assertEquals(listOf(true, true), snapshot.fields.map { it.wrap })
        assertEquals(snapshot.fields.size, snapshot.days.single().rows.single().cells[0]?.values?.size)
    }

    @Test
    fun `columns of the export follow the field definitions of the journal`() {
        // Экспорт и матрица берут поля из одного источника — состав колонок листа расходиться не может.
        val fields = listOf(testField("volume", title = "ОБЪЁМ", columnWidthDp = 72, maxLines = 1))
        val records = listOf(testRecord("r1", dateEpochDay = day1, contractorId = "A", values = mapOf("volume" to "12 м²")))

        val snapshot = buildJournalSnapshot("Июль 2026", records, listOf(contractor("A", 0)), fields)

        assertEquals(listOf("ОБЪЁМ"), snapshot.fields.map { it.title })
        // maxLines == 1 — единственный случай, когда перенос строк в ячейке листа выключен.
        assertEquals(listOf(false), snapshot.fields.map { it.wrap })
        assertEquals(listOf("12 м²"), snapshot.days.single().rows.single().cells[0]?.values)
    }
}
