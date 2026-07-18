package ru.papasheets.exportkit.xlsx.read

import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чтение разметки, которую наш экспорт не производит: общая таблица строк, даты Excel-serial'ом,
 * а также поведение на файлах, которые журналом не являются вовсе. Эталон
 * ([XlsxReaderReferenceTest]) закрыть эти случаи не может — из него вырезана `xl/sharedStrings.xml`,
 * поэтому разрешение индексов приходится проверять на собранном вручную файле.
 */
class XlsxReaderFormatTest {

    @Test
    fun `shared string indices resolve to contractor names and cell text`() {
        val sheet = XlsxReader.read(MinimalXlsx.withSharedStrings())

        assertEquals(listOf("Иванов", "Петров"), sheet.contractors)
        assertEquals(listOf("Л", "ВИД РАБОТ"), sheet.fieldTitles)
        assertEquals(listOf("1-01", "Штукатурка"), sheet.rows.single().cells[0]!!.values)
    }

    @Test
    fun `an excel serial in column A becomes a real date`() {
        val sheet = XlsxReader.read(MinimalXlsx.withSharedStrings())

        assertEquals(LocalDate.of(2026, 6, 1), sheet.days.single().date)
    }

    /**
     * Excel считает 1900 год високосным (совместимость с Lotus 1-2-3), поэтому до фальшивого
     * 29 февраля 1900 сдвиг эпохи другой, а сам этот serial реальной даты не имеет.
     */
    @Test
    fun `the 1900 leap year bug shifts early serials and voids the fake day`() {
        assertEquals(LocalDate.of(1900, 1, 1), ExcelSerialDate.toLocalDate(1.0))
        assertEquals(LocalDate.of(1900, 2, 28), ExcelSerialDate.toLocalDate(59.0))
        assertEquals(null, ExcelSerialDate.toLocalDate(60.0))
        assertEquals(LocalDate.of(1900, 3, 1), ExcelSerialDate.toLocalDate(61.0))
        assertEquals(LocalDate.of(2026, 6, 1), ExcelSerialDate.toLocalDate(46174.0))
        assertEquals(null, ExcelSerialDate.toLocalDate(0.0))
    }

    @Test
    fun `a file that is not a zip archive reports it in plain language`() {
        val notAZip = File.createTempFile("papasheets-broken", ".xlsx").apply {
            deleteOnExit()
            writeText("это вообще не таблица")
        }

        val error = assertThrowsFormat { XlsxReader.read(notAZip) }
        assertTrue(error.message.orEmpty().isNotBlank())
    }

    @Test
    fun `a zip without a workbook is rejected as not a spreadsheet`() {
        val zipOfSomethingElse = TestXlsx.zip(mapOf("readme.txt" to "привет".toByteArray()), "foreign")

        assertThrowsFormat { XlsxReader.read(zipOfSomethingElse) }
    }

    /** Таблица без merge-шапки и без «Ф» — структуру распознать нечем, и это надо сказать словами. */
    @Test
    fun `a spreadsheet that is not a journal matrix is rejected`() {
        assertThrowsFormat { XlsxReader.read(MinimalXlsx.withoutMatrixHeader()) }
    }

    @Test
    fun `a missing file is reported rather than thrown as NPE`() {
        assertThrowsFormat { XlsxReader.read(File("/nonexistent/papasheets.xlsx")) }
    }

    /**
     * Файл выбирает пользователь, поэтому внешняя сущность в нём — реальная поверхность атаки:
     * с настройками парсера по умолчанию `SYSTEM "file:///…"` был бы прочитан и утёк в ячейку.
     * Объявление doctype запрещено целиком, так что разбор обязан упасть, а не подставить секрет.
     */
    @Test
    fun `an external entity is never resolved`() {
        val secret = File.createTempFile("papasheets-secret", ".txt").apply {
            deleteOnExit()
            writeText("СЕКРЕТ")
        }

        val error = assertThrowsFormat { XlsxReader.read(MinimalXlsx.withExternalEntity(secret)) }
        assertFalse("содержимое чужого файла не должно попасть наружу", error.message.orEmpty().contains("СЕКРЕТ"))
    }

    private fun assertThrowsFormat(block: () -> Unit): XlsxFormatException {
        val error = try {
            block()
            null
        } catch (e: XlsxFormatException) {
            e
        }
        assertNotNull("ожидалось XlsxFormatException с понятным текстом", error)
        return error!!
    }
}

/** Минимальные xlsx, собранные вручную: только те части, без которых чтение невозможно. */
private object MinimalXlsx {

    private const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"

    private fun workbook(sheetName: String) =
        """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="$MAIN_NS" xmlns:r="$REL_NS">""" +
            """<sheets><sheet name="$sheetName" sheetId="1" r:id="rId1"/></sheets></workbook>"""

    private val workbookRels =
        """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="$PKG_REL_NS">""" +
            """<Relationship Id="rId1" Type="$REL_NS/worksheet" Target="worksheets/sheet1.xml"/></Relationships>"""

    private fun parts(sheet: String, sharedStrings: String? = null): Map<String, ByteArray> = buildMap {
        put("xl/workbook.xml", workbook("Июнь").toByteArray())
        put("xl/_rels/workbook.xml.rels", workbookRels.toByteArray())
        put("xl/worksheets/sheet1.xml", sheet.toByteArray())
        sharedStrings?.let { put("xl/sharedStrings.xml", it.toByteArray()) }
    }

    /** Шапка и данные так, как их пишет Google Sheets: весь текст — индексами в общую таблицу. */
    fun withSharedStrings(): File {
        val strings = listOf("ДАТА", "Иванов", "Петров", "Ф", "Л", "ВИД РАБОТ", "1-01", "Штукатурка")
        val shared = """<?xml version="1.0" encoding="UTF-8"?><sst xmlns="$MAIN_NS" count="${strings.size}">""" +
            strings.joinToString("") { "<si><t>$it</t></si>" } + "</sst>"

        val sheet = """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="$MAIN_NS" xmlns:r="$REL_NS"><sheetData>""" +
            """<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="E1" t="s"><v>2</v></c></row>""" +
            """<row r="2">""" +
            """<c r="B2" t="s"><v>3</v></c><c r="C2" t="s"><v>4</v></c><c r="D2" t="s"><v>5</v></c>""" +
            """<c r="E2" t="s"><v>3</v></c><c r="F2" t="s"><v>4</v></c><c r="G2" t="s"><v>5</v></c>""" +
            """</row>""" +
            """<row r="3"><c r="A3"><v>46174.0</v></c><c r="C3" t="s"><v>6</v></c><c r="D3" t="s"><v>7</v></c></row>""" +
            """</sheetData><mergeCells count="3"><mergeCell ref="A1:A2"/>""" +
            """<mergeCell ref="B1:D1"/><mergeCell ref="E1:G1"/></mergeCells></worksheet>"""

        return TestXlsx.zip(parts(sheet, shared), "shared")
    }

    /** Обычная таблица: ни merge-шапки подрядчиков, ни колонок «Ф». */
    fun withoutMatrixHeader(): File {
        val sheet = """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="$MAIN_NS"><sheetData>""" +
            """<row r="1"><c r="A1" t="inlineStr"><is><t>Приход</t></is></c></row>""" +
            """<row r="2"><c r="A2" t="inlineStr"><is><t>100</t></is></c></row>""" +
            """</sheetData></worksheet>"""
        return TestXlsx.zip(parts(sheet), "plain")
    }

    fun withExternalEntity(secret: File): File {
        val sheet = """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<!DOCTYPE worksheet [<!ENTITY xxe SYSTEM "file://${secret.absolutePath}">]>""" +
            """<worksheet xmlns="$MAIN_NS"><sheetData>""" +
            """<row r="1"><c r="B1" t="inlineStr"><is><t>&xxe;</t></is></c></row>""" +
            """</sheetData><mergeCells count="1"><mergeCell ref="B1:D1"/></mergeCells></worksheet>"""
        return TestXlsx.zip(parts(sheet), "xxe")
    }
}
