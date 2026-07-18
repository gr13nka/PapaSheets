package ru.papasheets.exportkit.xlsx.read

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.papasheets.exportkit.TestFixtures
import ru.papasheets.exportkit.model.JournalSnapshot
import ru.papasheets.exportkit.model.PhotoBytesProvider
import ru.papasheets.exportkit.model.SnapshotField
import ru.papasheets.exportkit.xlsx.XlsxWriter

/**
 * Экспорт → импорт: то, что мы записали, должно прочитаться обратно тем же.
 *
 * Это единственная проверка, которая ловит расхождение писателя и читателя по раскладке. Оба берут
 * её из [ru.papasheets.exportkit.xlsx.MatrixSheetLayout], но общая константа сама по себе ничего не
 * гарантирует — сойтись формулы обязаны на реальном файле.
 */
class XlsxRoundTripTest {

    private fun writeThenRead(snapshot: JournalSnapshot, photos: PhotoBytesProvider?): ParsedSheet {
        val file = File.createTempFile("papasheets-roundtrip", ".xlsx")
        file.deleteOnExit()
        file.outputStream().use { XlsxWriter.write(snapshot, photos, it) }
        return XlsxReader.read(file)
    }

    @Test
    fun `contractors, field titles and values survive the round trip`() {
        val snapshot = TestFixtures.snapshot()
        val sheet = writeThenRead(snapshot, TestFixtures.photoProvider())

        assertEquals(snapshot.contractors.map { it.name }, sheet.contractors)
        assertEquals(snapshot.fields.map { it.title }, sheet.fieldTitles)

        // Текст с кавычками, амперсандом, угловыми скобками и переносом строки обязан вернуться дословно.
        val expected = snapshot.days.flatMap { day -> day.rows.map { row -> row.cells.map { it?.values } } }
        val actual = sheet.days.flatMap { day -> day.rows.map { row -> row.cells.map { it?.values } } }
        assertEquals(expected, actual)
    }

    @Test
    fun `days keep their labels and order`() {
        val snapshot = TestFixtures.snapshot()
        val sheet = writeThenRead(snapshot, TestFixtures.photoProvider())

        assertEquals(snapshot.days.map { it.dateLabel }, sheet.days.map { it.dateLabel })
        // Наш экспорт пишет «01.07» без года — года взяться неоткуда, и выдумывать его читатель не должен.
        assertTrue(sheet.days.all { it.date == null })
    }

    @Test
    fun `each photo comes back on its own record, with its bytes`() {
        val snapshot = TestFixtures.snapshot()
        val sheet = writeThenRead(snapshot, TestFixtures.photoProvider())

        // Фото было у первого подрядчика в первый день и у второго — во второй.
        val firstDay = sheet.days[0].rows.single()
        val secondDay = sheet.days[1].rows.single()
        assertTrue(firstDay.cells[0]!!.photo!!.isPresent)
        assertNull(firstDay.cells[1])
        assertNull(secondDay.cells[0]!!.photo)
        assertTrue(secondDay.cells[1]!!.photo!!.isPresent)

        assertArrayEquals(
            TestFixtures.jpegBytes(TestFixtures.photoASize.first, TestFixtures.photoASize.second),
            sheet.photoBytes(firstDay.cells[0]!!.photo!!),
        )
    }

    @Test
    fun `a journal without photos reads back without them`() {
        val sheet = writeThenRead(TestFixtures.snapshot(), photos = null)

        assertTrue(sheet.rows.flatMap { it.cells }.filterNotNull().all { it.photo == null })
    }

    /**
     * Число полей — переменное (M9), и раскладка обязана сходиться при любом. Ноль полей отдельно
     * важен: групповых merge в таком файле нет вовсе, и шапку читатель распознаёт по меткам «Ф».
     */
    @Test
    fun `the round trip holds for field counts other than the historical two`() {
        for (count in listOf(0, 1, 4)) {
            val fields = List(count) { SnapshotField(title = "Поле $it", widthChars = 10.0, wrap = false) }
            val snapshot = TestFixtures.snapshotWithFields(fields)
            val sheet = writeThenRead(snapshot, TestFixtures.photoProvider())

            assertEquals("полей: $count", listOf("Иванов", "Петров"), sheet.contractors)
            assertEquals("полей: $count", fields.map { it.title }, sheet.fieldTitles)
            assertEquals("полей: $count", fields.indices.map { "b$it" }, sheet.rows.single().cells[1]!!.values)
            assertTrue("полей: $count", sheet.rows.single().cells[1]!!.photo!!.isPresent)
        }
    }
}
