package ru.papasheets.exportkit.xlsx.read

import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор настоящего файла заказчика — распакованного «Copy of Июнь.xlsx» из
 * `docs/reference/iyun-xlsx`. Это единственный тест, который проверяет чтение на разметке, которую
 * писали не мы: Google Sheets раскладывает лист иначе, чем [ru.papasheets.exportkit.xlsx.XlsxWriter]
 * (общая таблица строк вместо инлайновой, даты числом вместо текста), и «работает на своём экспорте»
 * про такой файл ничего не доказывает.
 *
 * Из дампа вырезаны `xl/media` (25 JPEG ≈ 5.5 МБ) — и вместе с ними, что важнее,
 * `xl/sharedStrings.xml`, хотя `[Content_Types].xml` её объявляет. Поэтому весь текст листа здесь
 * недоступен в принципе, и проверять на эталоне можно только то, что от текста не зависит:
 * геометрию шапки, даты и привязку фото. Разрешение самих общих строк проверяется отдельно
 * ([XlsxReaderFormatTest]) на собранном вручную файле.
 */
class XlsxReaderReferenceTest {

    private fun referenceSheet(): ParsedSheet = XlsxReader.read(TestXlsx.zipReference())

    @Test
    fun `header merges give 28 contractors in groups of three columns`() {
        val sheet = referenceSheet()

        // 29 merge-диапазонов: A1:A2 плюс по одному на подрядчика; группа B1:D1 = Ф + Л + ВИД РАБОТ.
        assertEquals(28, sheet.contractors.size)
        assertEquals(2, sheet.fieldTitles.size)
        assertEquals("Лист2", sheet.sheetName)
    }

    /**
     * Имена подрядчиков и подписи полей в этом файле лежат в вырезанной `xl/sharedStrings.xml`.
     * Читатель обязан пережить это без падения: структура листа от текста не зависит, а пустые
     * имена увидит человек в предпросмотре — и решит, импортировать или нет.
     */
    @Test
    fun `missing shared strings leave text empty instead of failing the read`() {
        val sheet = referenceSheet()

        assertTrue("имена подрядчиков должны быть пустыми, а не выдуманными", sheet.contractors.all { it.isEmpty() })
        assertTrue(sheet.fieldTitles.all { it.isEmpty() })
    }

    @Test
    fun `dates come from excel serials, not from text`() {
        val sheet = referenceSheet()

        // <v>46174.0</v> и соседние: 1–3 июня 2026 года.
        assertEquals(
            listOf(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 2), LocalDate.of(2026, 6, 3)),
            sheet.days.map { it.date },
        )
    }

    @Test
    fun `data rows stop where the sheet stops, not at the declared dimension`() {
        val sheet = referenceSheet()

        // На листе 1298 строк, но заполнены только 3..39 — остальное пустая разметка.
        assertEquals(37, sheet.rows.size)
        assertEquals(3, sheet.rows.first().sheetRow)
        assertEquals(39, sheet.rows.last().sheetRow)
    }

    @Test
    fun `photo anchors bind pictures to the contractor whose group they sit in`() {
        val sheet = referenceSheet()

        val withPhoto = sheet.rows.flatMap { row ->
            row.cells.mapIndexedNotNull { index, cell -> cell?.photos?.firstOrNull()?.let { index to it } }
        }
        assertEquals(25, withPhoto.size)

        // Якоря стоят на 0-based колонках 1, 4, 10, 13 — это Ф-колонки подрядчиков 0, 1, 3 и 4.
        assertEquals(setOf(0, 1, 3, 4), withPhoto.map { it.first }.toSet())
        assertTrue(withPhoto.all { it.second.entryName.startsWith("xl/media/") })
    }

    /** Байтов фото в дампе нет — ссылка обязана остаться, но пустой, а не превратиться в исключение. */
    @Test
    fun `absent media bytes yield an empty reference rather than an error`() {
        val sheet = referenceSheet()
        val ref = sheet.rows.firstNotNullOf { row -> row.cells.firstNotNullOfOrNull { it?.photos?.firstOrNull() } }

        assertFalse(ref.isPresent)
        assertNotNull(ref.entryName)
        assertEquals(null, sheet.photoBytes(ref))
    }
}

/** Сборка временных xlsx для тестов чтения. */
internal object TestXlsx {

    /** Каталог эталона относительно рабочего каталога тестов (`exportkit/`). */
    private val REFERENCE_DIR = File("../docs/reference/iyun-xlsx")

    /** Эталон лежит в репозитории распакованным (иначе бинарь в git) — упаковываем обратно. */
    fun zipReference(): File {
        require(REFERENCE_DIR.isDirectory) { "нет каталога эталона: ${REFERENCE_DIR.absolutePath}" }
        val parts = LinkedHashMap<String, ByteArray>()
        REFERENCE_DIR.walkTopDown().filter { it.isFile && it.name != "README.md" }.forEach { file ->
            parts[file.relativeTo(REFERENCE_DIR).invariantPath()] = file.readBytes()
        }
        return zip(parts, "reference")
    }

    fun zip(parts: Map<String, ByteArray>, name: String): File {
        val file = File.createTempFile("papasheets-$name", ".xlsx")
        file.deleteOnExit()
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            for ((entryName, bytes) in parts) {
                zip.putNextEntry(java.util.zip.ZipEntry(entryName))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun File.invariantPath(): String = path.replace(File.separatorChar, '/')
}
