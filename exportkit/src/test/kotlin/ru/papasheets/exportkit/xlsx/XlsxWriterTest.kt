package ru.papasheets.exportkit.xlsx

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import ru.papasheets.exportkit.TestFixtures

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

        val mergeRefs = sheet.elements("mergeCell").map { it.getAttribute("ref") }
        assertEquals(setOf("A1:A2", "B1:D1", "E1:G1"), mergeRefs.toSet())

        val pane = sheet.elements("pane").single()
        assertEquals("1", pane.getAttribute("xSplit"))
        assertEquals("2", pane.getAttribute("ySplit"))
        assertEquals("B3", pane.getAttribute("topLeftCell"))
        assertEquals("frozen", pane.getAttribute("state"))

        val cols = sheet.elements("col")
        assertEquals("10.5", cols[0].getAttribute("width"))
        assertEquals("5.75", cols[1].getAttribute("width"))
        assertEquals("7.75", cols[2].getAttribute("width"))
        assertEquals("50.75", cols[3].getAttribute("width"))

        assertEquals("ДАТА", sheet.cellText("A1"))
        assertEquals("Иванов", sheet.cellText("B1"))
        assertEquals("Петров & \"Сыновья\"", sheet.cellText("E1"))
        assertEquals("Ф", sheet.cellText("B2"))
        assertEquals("Л", sheet.cellText("C2"))
        assertEquals("ВИД РАБОТ", sheet.cellText("D2"))

        // Строка 3 = день1: только подрядчик1 (с фото), подрядчик2 в этот день пуст.
        assertEquals("01.07", sheet.cellText("A3"))
        assertNull(sheet.cellText("B3")) // Ф — под фото, без текста
        assertEquals("1-01", sheet.cellText("C3"))
        assertEquals("Штукатурка <потолок>\nвторая строка", sheet.cellText("D3"))
        assertNull(sheet.cellText("E3"))
        assertNull(sheet.cellText("F3"))
        assertNull(sheet.cellText("G3"))

        // Строка 4 = день2: оба подрядчика заполнены, только подрядчик2 с фото.
        assertEquals("02.07", sheet.cellText("A4"))
        assertNull(sheet.cellText("B4"))
        assertEquals("1-02", sheet.cellText("C4"))
        assertEquals("Заливка пола", sheet.cellText("D4"))
        assertNull(sheet.cellText("E4"))
        assertEquals("2-05; доп", sheet.cellText("F4"))
        assertEquals("Кладка \"кирпич\" & раствор", sheet.cellText("G4"))

        // Обе строки данных содержат фото — обе должны быть утолщены.
        assertEquals("90.0", sheet.row("3").getAttribute("ht"))
        assertEquals("1", sheet.row("3").getAttribute("customHeight"))
        assertEquals("90.0", sheet.row("4").getAttribute("ht"))
    }

    @Test
    fun `xlsx with photos has two oneCellAnchor drawings wired through rels`() {
        val entries = writeZip(withPhotos = true)
        val sheet = parse(entries.getValue("xl/worksheets/sheet1.xml"))
        assertEquals(1, sheet.elements("drawing").size)

        val drawing = parse(entries.getValue("xl/drawings/drawing1.xml"))
        val anchors = drawing.elements("xdr:oneCellAnchor")
        assertEquals(2, anchors.size)

        val from0 = (anchors[0].getElementsByTagName("xdr:from").item(0) as Element)
        assertEquals("1", (from0.getElementsByTagName("xdr:col").item(0)).textContent) // Ф подрядчика1
        assertEquals("2", (from0.getElementsByTagName("xdr:row").item(0)).textContent) // 1-я строка данных

        val from1 = (anchors[1].getElementsByTagName("xdr:from").item(0) as Element)
        assertEquals("4", (from1.getElementsByTagName("xdr:col").item(0)).textContent) // Ф подрядчика2
        assertEquals("3", (from1.getElementsByTagName("xdr:row").item(0)).textContent) // 2-я строка данных

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
        assertEquals(setOf("../media/photo-a.jpg", "../media/photo-b.jpg"), relTargets)

        val blipEmbeds = drawing.elements("a:blip").map { it.getAttribute("r:embed") }.toSet()
        assertEquals(relIds, blipEmbeds)

        assertTrue(entries.containsKey("xl/media/photo-a.jpg"))
        assertTrue(entries.containsKey("xl/media/photo-b.jpg"))

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
        assertEquals("1-01", sheet.cellText("C3"))
        assertEquals("2-05; доп", sheet.cellText("F4"))
        // Без фото строки не утолщаются даже там, где раньше стояло фото.
        assertTrue(sheet.row("3").getAttribute("ht").isEmpty())

        val contentTypes = parse(entries.getValue("[Content_Types].xml"))
        assertTrue(contentTypes.elements("Default").none { it.getAttribute("Extension") == "jpg" })
    }
}
