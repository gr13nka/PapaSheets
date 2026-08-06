package ru.papasheets.exportkit.xlsx

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import ru.papasheets.exportkit.TestFixtures
import ru.papasheets.exportkit.model.JournalSnapshot
import ru.papasheets.exportkit.model.SnapshotField

/**
 * Раскладка листа при числе полей, отличном от исторических двух: шаг колонок, merge-шапка,
 * колонка якоря фото и настройки печати. [XlsxWriterTest] проверяет тот же лист на «легаси»-наборе
 * из двух полей и служит регресс-сверкой формата — здесь же именно переменная часть.
 */
class SheetLayoutTest {

    private fun fields(count: Int) = List(count) {
        SnapshotField(title = "Поле $it", widthChars = 10.0 + it, wrap = it % 2 == 1)
    }

    private fun parse(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }

    private fun Document.elements(tag: String): List<Element> {
        val list = getElementsByTagName(tag)
        return (0 until list.length).map { list.item(it) as Element }
    }

    private fun Document.cellText(ref: String): String? {
        val cell = elements("c").firstOrNull { it.getAttribute("r") == ref } ?: return null
        val isNodes = cell.getElementsByTagName("is")
        if (isNodes.length == 0) return null
        val tNodes = (isNodes.item(0) as Element).getElementsByTagName("t")
        return if (tNodes.length == 0) "" else tNodes.item(0).textContent
    }

    private fun sheetOf(snapshot: JournalSnapshot, hasDrawing: Boolean = false): Document =
        parse(SheetXml.build(snapshot, hasDrawing))

    @Test
    fun `four fields widen the contractor group to six columns`() {
        val sheet = sheetOf(TestFixtures.snapshotWithFields(fields(4)))

        // Группа = 2 колонки Ф + 4 поля; 2 подрядчика → A + 2*6 = 13 колонок (последняя M).
        assertEquals("A1:M3", sheet.elements("dimension").single().getAttribute("ref"))
        assertEquals(13, sheet.elements("col").size)

        assertEquals(listOf("A1:A2", "B1:G1", "H1:M1"), sheet.elements("mergeCell").map { it.getAttribute("ref") })
        assertEquals("3", sheet.elements("mergeCells").single().getAttribute("count"))

        // Порядок <cols>: дата, затем на каждую группу обе Ф и ширины полей по порядку.
        // Ф — 12.29: ширина колонки считается от высоты строки с фото (PHOTO_ROW_HEIGHT_PT), а не
        // берётся из эталона: фото обязано помещаться в ячейку с полем, иначе просмотрщик на
        // телефоне его не рисует. Было 9.95 до 2026-08-06 (см. PhotoFitTest).
        val widths = sheet.elements("col").map { it.getAttribute("width") }
        assertEquals(
            listOf(
                "10.5",
                "12.29", "12.29", "10.0", "11.0", "12.0", "13.0",
                "12.29", "12.29", "10.0", "11.0", "12.0", "13.0",
            ),
            widths,
        )

        // Шапка и данные обеих групп идут с тем же шагом.
        assertEquals("Ф", sheet.cellText("B2"))
        assertEquals("Ф", sheet.cellText("C2"))
        assertEquals("Поле 0", sheet.cellText("D2"))
        assertEquals("Поле 3", sheet.cellText("G2"))
        assertEquals("Ф", sheet.cellText("H2"))
        assertEquals("Ф", sheet.cellText("I2"))
        assertEquals("Поле 0", sheet.cellText("J2"))
        assertEquals("a0", sheet.cellText("D3"))
        assertEquals("a3", sheet.cellText("G3"))
        assertEquals("b0", sheet.cellText("J3"))
        assertEquals("b3", sheet.cellText("M3"))
    }

    @Test
    fun `photo anchors follow the group width, not a hardcoded three columns`() {
        val out = ByteArrayOutputStream()
        XlsxWriter.write(TestFixtures.snapshotWithFields(fields(4)), TestFixtures.photoProvider(), out)

        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }

        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val drawing = factory.newDocumentBuilder().parse(ByteArrayInputStream(entries.getValue("xl/drawings/drawing1.xml")))
        val anchors = (0 until drawing.getElementsByTagName("xdr:oneCellAnchor").length)
            .map { drawing.getElementsByTagName("xdr:oneCellAnchor").item(it) as Element }
        assertEquals(1, anchors.size)

        // Фото у второго подрядчика, слот 0: первая Ф группы = 1 + 6*1 = 7 (0-based), т.е. столбец H.
        val from = anchors[0].getElementsByTagName("xdr:from").item(0) as Element
        assertEquals("7", from.getElementsByTagName("xdr:col").item(0).textContent)
        assertEquals("2", from.getElementsByTagName("xdr:row").item(0).textContent)
    }

    /**
     * Ноль полей больше не схлопывает группу в одну колонку: колонок Ф две, значит merge из одной
     * ячейки (который Excel считает повреждением файла) при таком наборе просто не получается.
     * Проверка на вырожденный merge остаётся — она сторожит само правило, а не текущее число Ф.
     */
    @Test
    fun `no fields still merges each contractor over both photo columns`() {
        val sheet = sheetOf(TestFixtures.snapshotWithFields(emptyList()))

        val merges = sheet.elements("mergeCell").map { it.getAttribute("ref") }
        assertEquals(listOf("A1:A2", "B1:C1", "D1:E1"), merges)
        assertEquals("3", sheet.elements("mergeCells").single().getAttribute("count"))
        assertTrue(merges.none { it.substringBefore(':') == it.substringAfter(':') })

        assertEquals("A1:E3", sheet.elements("dimension").single().getAttribute("ref"))
        assertEquals("Ф", sheet.cellText("B2"))
        assertEquals("Ф", sheet.cellText("C2"))
        assertEquals("Ф", sheet.cellText("D2"))
        assertEquals("Ф", sheet.cellText("E2"))
    }

    @Test
    fun `print setup fits page width, repeats header and keeps schema element order`() {
        val snapshot = TestFixtures.snapshot()
        val xml = SheetXml.build(snapshot, hasDrawing = true)
        val sheet = parse(xml)

        assertEquals("1", sheet.elements("pageSetUpPr").single().getAttribute("fitToPage"))
        val pageSetup = sheet.elements("pageSetup").single()
        assertEquals("landscape", pageSetup.getAttribute("orientation"))
        assertEquals("1", pageSetup.getAttribute("fitToWidth"))
        assertEquals("0", pageSetup.getAttribute("fitToHeight"))
        assertEquals(1, sheet.elements("printOptions").size)
        assertEquals(1, sheet.elements("pageMargins").size)

        // Excel отвергает файл при нарушении порядка элементов внутри <worksheet>.
        val order = listOf(
            "<sheetPr>", "<dimension", "<sheetViews>", "<sheetFormatPr", "<cols>",
            "<sheetData>", "<mergeCells", "<printOptions", "<pageMargins", "<pageSetup", "<drawing",
        ).map { xml.indexOf(it) }
        assertTrue("не все элементы листа найдены: $order", order.none { it < 0 })
        assertEquals(order.sorted(), order)
    }

    private fun workbookXml(snapshot: JournalSnapshot): String {
        val out = ByteArrayOutputStream()
        XlsxWriter.write(snapshot, TestFixtures.photoProvider(), out)
        var workbook: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "xl/workbook.xml") workbook = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        return String(workbook!!, Charsets.UTF_8)
    }

    @Test
    fun `print titles repeat the header rows of the sheet`() {
        val xml = workbookXml(TestFixtures.snapshot())
        val definedName = parse(xml).elements("definedName").single()

        assertEquals("_xlnm.Print_Titles", definedName.getAttribute("name"))
        assertEquals("0", definedName.getAttribute("localSheetId"))
        assertEquals("'Июль 2026'!${'$'}1:${'$'}2", definedName.textContent)

        // definedNames по схеме идут после sheets.
        assertTrue(xml.indexOf("<sheets>") < xml.indexOf("<definedNames>"))
    }

    /** Апостроф в имени листа Excel требует удвоения — иначе имя обрывается на нём. */
    @Test
    fun `an apostrophe in the title is doubled inside the print titles reference`() {
        val snapshot = JournalSnapshot(
            title = "Дом О'Коннора",
            contractors = TestFixtures.snapshot().contractors,
            fields = TestFixtures.legacyFields,
            days = emptyList(),
        )
        val workbook = parse(workbookXml(snapshot))

        assertEquals("Дом О'Коннора", workbook.elements("sheet").single().getAttribute("name"))
        assertEquals("'Дом О''Коннора'!${'$'}1:${'$'}2", workbook.elements("definedName").single().textContent)
    }
}
