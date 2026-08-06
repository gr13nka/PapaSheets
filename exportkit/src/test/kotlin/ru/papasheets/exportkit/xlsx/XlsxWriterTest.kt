package ru.papasheets.exportkit.xlsx

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import ru.papasheets.exportkit.TestFixtures
import ru.papasheets.exportkit.model.JournalSnapshot
import ru.papasheets.exportkit.model.SnapshotCell
import ru.papasheets.exportkit.model.SnapshotContractor
import ru.papasheets.exportkit.model.SnapshotDay
import ru.papasheets.exportkit.model.SnapshotRow

class XlsxWriterTest {

    private fun writeZip(withPhotos: Boolean): Map<String, ByteArray> {
        val out = ByteArrayOutputStream()
        XlsxWriter.write(TestFixtures.snapshot(), if (withPhotos) TestFixtures.photoProvider() else null, out)
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    private fun parse(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun Document.elements(tag: String): List<Element> {
        val list = getElementsByTagName(tag)
        return (0 until list.length).map { list.item(it) as Element }
    }

    /** Текст inlineStr-ячейки по адресу или null, если ячейка пуста (нет `<is>`). */
    private fun Document.cellText(ref: String): String? {
        val cell = elements("c").first { it.getAttribute("r") == ref }
        val isNodes = cell.getElementsByTagName("is")
        if (isNodes.length == 0) return null
        val tNodes = (isNodes.item(0) as Element).getElementsByTagName("t")
        return if (tNodes.length == 0) "" else tNodes.item(0).textContent
    }

    private fun Document.row(r: String): Element = elements("row").first { it.getAttribute("r") == r }

    @Test
    fun `xlsx with photos has correct merges, freeze, widths, header and inline strings`() {
        val entries = writeZip(withPhotos = true)
        val sheet = parse(entries.getValue("xl/worksheets/sheet1.xml"))

        // Группа = 2 колонки Ф + 2 поля, значит шаг 4: B..E у первого подрядчика, F..I у второго.
        val mergeRefs = sheet.elements("mergeCell").map { it.getAttribute("ref") }
        assertEquals(setOf("A1:A2", "B1:E1", "F1:I1"), mergeRefs.toSet())

        val pane = sheet.elements("pane").single()
        assertEquals("1", pane.getAttribute("xSplit"))
        assertEquals("2", pane.getAttribute("ySplit"))
        assertEquals("B3", pane.getAttribute("topLeftCell"))
        assertEquals("frozen", pane.getAttribute("state"))

        val cols = sheet.elements("col")
        assertEquals("10.5", cols[0].getAttribute("width"))
        // Обе Ф считаются от стороны фото-квадрата (PHOTO_BOX_PT = 56pt), а не задаются отдельно.
        // 9.95 → 10.72 (2026-08-06): Widths.ptToChars вычитала 5px полей ячейки, которые в единицу
        // ширины уже входят, и колонка выходила 70px при фото в 74.67px. Инвариант — PhotoFitTest.
        assertEquals("10.72", cols[1].getAttribute("width"))
        assertEquals("10.72", cols[2].getAttribute("width"))
        assertEquals("7.75", cols[3].getAttribute("width"))
        assertEquals("50.75", cols[4].getAttribute("width"))

        assertEquals("ДАТА", sheet.cellText("A1"))
        assertEquals("Иванов", sheet.cellText("B1"))
        assertEquals("Петров & \"Сыновья\"", sheet.cellText("F1"))
        // Обе колонки фото подписаны одинаково — читатель считает длину серии, а не различает подписи.
        assertEquals("Ф", sheet.cellText("B2"))
        assertEquals("Ф", sheet.cellText("C2"))
        assertEquals("Л", sheet.cellText("D2"))
        assertEquals("ВИД РАБОТ", sheet.cellText("E2"))

        // Строка 3 = день1: только подрядчик1 (одно фото), подрядчик2 в этот день пуст.
        assertEquals("01.07", sheet.cellText("A3"))
        assertNull(sheet.cellText("B3")) // Ф — под фото, без текста
        assertNull(sheet.cellText("C3"))
        assertEquals("1-01", sheet.cellText("D3"))
        assertEquals("Штукатурка <потолок>\nвторая строка", sheet.cellText("E3"))
        assertNull(sheet.cellText("F3"))
        assertNull(sheet.cellText("H3"))
        assertNull(sheet.cellText("I3"))

        // Строка 4 = день2: оба подрядчика заполнены, у подрядчика2 два фото.
        assertEquals("02.07", sheet.cellText("A4"))
        assertNull(sheet.cellText("B4"))
        assertEquals("1-02", sheet.cellText("D4"))
        assertEquals("Заливка пола", sheet.cellText("E4"))
        assertNull(sheet.cellText("F4"))
        assertNull(sheet.cellText("G4"))
        assertEquals("2-05; доп", sheet.cellText("H4"))
        assertEquals("Кладка \"кирпич\" & раствор", sheet.cellText("I4"))

        // Обе строки данных содержат фото — обе получают явную высоту. Текста здесь не больше двух
        // строк (30pt), поэтому высоту задаёт фото; с пяти строк (75pt) её задавал бы уже текст.
        assertEquals("64.0", sheet.row("3").getAttribute("ht"))
        assertEquals("1", sheet.row("3").getAttribute("customHeight"))
        assertEquals("64.0", sheet.row("4").getAttribute("ht"))
    }

    @Test
    fun `xlsx with photos has one oneCellAnchor per photo wired through rels`() {
        val entries = writeZip(withPhotos = true)
        val sheet = parse(entries.getValue("xl/worksheets/sheet1.xml"))
        assertEquals(1, sheet.elements("drawing").size)

        val drawing = parse(entries.getValue("xl/drawings/drawing1.xml"))
        val anchors = drawing.elements("xdr:oneCellAnchor")
        // Три фото на три якоря: одно у подрядчика1 и два у подрядчика2.
        assertEquals(3, anchors.size)

        fun anchorCell(index: Int): Pair<String, String> {
            val from = anchors[index].getElementsByTagName("xdr:from").item(0) as Element
            return from.getElementsByTagName("xdr:col").item(0).textContent to
                from.getElementsByTagName("xdr:row").item(0).textContent
        }

        // 0-based: B=1, F=5, G=6; строка данных 1 = 2, строка данных 2 = 3.
        assertEquals("1" to "2", anchorCell(0)) // Ф1 подрядчика1, 1-я строка данных
        assertEquals("5" to "3", anchorCell(1)) // Ф1 подрядчика2, 2-я строка данных
        assertEquals("6" to "3", anchorCell(2)) // Ф2 того же подрядчика — второе фото записи

        val ext0 = anchors[0].getElementsByTagName("xdr:ext").item(0) as Element
        val (w0, h0) = TestFixtures.photoASize
        val cx0 = ext0.getAttribute("cx").toDouble()
        val cy0 = ext0.getAttribute("cy").toDouble()
        assertEquals(w0.toDouble() / h0.toDouble(), cx0 / cy0, 0.01)

        // rId-цепочка: workbook→sheet→drawing→media должна совпадать в обе стороны.
        val sheetRels = parse(entries.getValue("xl/worksheets/_rels/sheet1.xml.rels"))
        assertEquals("../drawings/drawing1.xml", sheetRels.elements("Relationship").single().getAttribute("Target"))

        val drawingRels = parse(entries.getValue("xl/drawings/_rels/drawing1.xml.rels"))
        val relIds = drawingRels.elements("Relationship").map { it.getAttribute("Id") }.toSet()
        val relTargets = drawingRels.elements("Relationship").map { it.getAttribute("Target") }.toSet()
        assertEquals(
            setOf("../media/photo-a.jpg", "../media/photo-b.jpg", "../media/photo-c.jpg"),
            relTargets,
        )

        val blipEmbeds = drawing.elements("a:blip").map { it.getAttribute("r:embed") }.toSet()
        assertEquals(relIds, blipEmbeds)

        assertTrue(entries.containsKey("xl/media/photo-a.jpg"))
        assertTrue(entries.containsKey("xl/media/photo-b.jpg"))
        assertTrue(entries.containsKey("xl/media/photo-c.jpg"))

        val contentTypes = parse(entries.getValue("[Content_Types].xml"))
        assertTrue(contentTypes.elements("Default").any { it.getAttribute("Extension") == "jpg" })
        assertTrue(contentTypes.elements("Override").any { it.getAttribute("PartName") == "/xl/drawings/drawing1.xml" })
    }

    @Test
    fun `xlsx without photos has no drawing or media but same data`() {
        val entries = writeZip(withPhotos = false)

        assertTrue(entries.keys.none { it.contains("drawing") })
        assertTrue(entries.keys.none { it.startsWith("xl/media/") })

        val sheet = parse(entries.getValue("xl/worksheets/sheet1.xml"))
        assertEquals(0, sheet.elements("drawing").size)
        assertEquals("1-01", sheet.cellText("D3"))
        assertEquals("2-05; доп", sheet.cellText("H4"))
        // Без фото строки не утолщаются даже там, где раньше стояло фото.
        assertTrue(sheet.row("3").getAttribute("ht").isEmpty())

        val contentTypes = parse(entries.getValue("[Content_Types].xml"))
        assertTrue(contentTypes.elements("Default").none { it.getAttribute("Extension") == "jpg" })
    }

    @Test
    fun `log parameter does not change written bytes`() {
        val silent = ByteArrayOutputStream()
        XlsxWriter.write(TestFixtures.snapshot(), TestFixtures.photoProvider(), silent)

        val logged = ByteArrayOutputStream()
        val collected = mutableListOf<String>()
        XlsxWriter.write(TestFixtures.snapshot(), TestFixtures.photoProvider(), logged, log = collected::add)

        assertArrayEquals(silent.toByteArray(), logged.toByteArray())
        // Побочный эффект случился — лог не превратился в молчаливую заглушку по ошибке.
        assertTrue(collected.isNotEmpty())
    }

    @Test
    fun `log reports truncation when a cell has more photos than columns`() {
        // У подрядчика три фото при лимите в PHOTO_COLUMNS_PER_GROUP = 2 колонки.
        val snapshot = JournalSnapshot(
            title = "Июль 2026",
            contractors = listOf(SnapshotContractor(name = "Иванов")),
            fields = TestFixtures.legacyFields,
            days = listOf(
                SnapshotDay(
                    dateLabel = "01.07",
                    rows = listOf(
                        SnapshotRow(
                            cells = listOf(
                                SnapshotCell(
                                    listOf("1-01", "Штукатурка"),
                                    photoIds = listOf(TestFixtures.PHOTO_A, TestFixtures.PHOTO_B, TestFixtures.PHOTO_C),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val collected = mutableListOf<String>()
        XlsxWriter.write(snapshot, TestFixtures.photoProvider(), ByteArrayOutputStream(), log = collected::add)

        assertTrue(collected.any { it.contains("3") && it.contains("отброшено") })
    }
}
