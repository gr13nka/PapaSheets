package ru.papasheets.exportkit.xlsx

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import ru.papasheets.exportkit.TestFixtures
import ru.papasheets.exportkit.model.JournalSnapshot
import ru.papasheets.exportkit.model.PhotoBytesProvider
import ru.papasheets.exportkit.model.SnapshotCell
import ru.papasheets.exportkit.model.SnapshotContractor
import ru.papasheets.exportkit.model.SnapshotDay
import ru.papasheets.exportkit.model.SnapshotRow

/**
 * Фото обязано влезать в свою колонку «Ф» по ширине — при любых пропорциях кадра.
 *
 * Это не косметика. Просмотрщик (Excel и Google Sheets на телефоне) ужимает картинку, которая
 * не поместилась в ячейку-якорь, и она проваливается ниже порога, с которого он вообще рисует
 * встроенные картинки (правило и замеры — docs/evolution.md, «xlsx»). Первым страдает
 * горизонтальный кадр: у него длинная сторона идёт поперёк колонки, тогда как у вертикального
 * поперёк колонки короткая. Живой случай 2026-08-06 («Август 2026.xlsx»): просмотрщик ужал ровно
 * 4 горизонтальных фото из 22 — с 74.67px до ширины колонки в 70px — и они пропали с экрана,
 * а 18 вертикальных остались нетронутыми.
 *
 * Сверка идёт с шириной колонки из самого листа, а не с [PHOTO_BOX_PT]: инвариант связывает два
 * независимо посчитанных числа, и подставлять в обе стороны один источник — значит не проверять
 * ничего.
 */
class PhotoFitTest {

    /** Пропорции кадра; абсолютные пиксели не важны — [XlsxWriter] вписывает фото по отношению сторон. */
    private val aspects = listOf(
        "9:16" to (90 to 160),
        "3:4" to (90 to 120),
        "1:1" to (120 to 120),
        "4:3" to (120 to 90),
        "16:9" to (160 to 90),
    )

    /**
     * Обратный ход Excel: сохранённая ширина колонки → пиксели (ECMA-376, `CT_Col/@width`).
     * Оракул нарочно не выражен через [Widths] — иначе тест повторил бы ошибку проверяемой формулы.
     * Сверен с живым файлом: наши `9.95` просмотрщик показал как 70px и записал обратно как `10.0`.
     */
    private fun excelPx(width: Double): Int = (((256 * width + 128 / MDW) / 256) * MDW).toInt()

    @Test
    fun `column width fits the points it was asked for`() {
        for (pt in listOf(28.0, 42.0, 52.0, 56.0, 64.0, 90.0)) {
            val requested = pt * PX_PER_POINT
            val actual = excelPx(Widths.ptToChars(pt))
            assertTrue(
                "$pt pt: колонка вышла $actual px при заказанных $requested px",
                actual >= requested,
            )
        }
    }

    @Test
    fun `photo of any aspect ratio fits its photo column`() {
        val entries = writeZip()
        val columnPx = excelPx(photoColumnWidth(parse(entries.getValue("xl/worksheets/sheet1.xml"))))
        val drawing = parse(entries.getValue("xl/drawings/drawing1.xml"))

        val widths = drawing.elements("xdr:ext").map { it.getAttribute("cx").toDouble() / EMU_PER_PX }
        aspects.zip(widths).forEach { (aspect, photoPx) ->
            assertTrue(
                "${aspect.first}: фото ${photoPx}px шире своей колонки Ф в ${columnPx}px — " +
                    "просмотрщик ужмёт его и картинка пропадёт",
                photoPx <= columnPx,
            )
        }
    }

    /** Ширина первой колонки «Ф»: колонка A — дата, группа подрядчика начинается со второй. */
    private fun photoColumnWidth(sheet: Document): Double =
        sheet.elements("col").first { it.getAttribute("min") == "2" }.getAttribute("width").toDouble()

    private fun writeZip(): Map<String, ByteArray> {
        val out = ByteArrayOutputStream()
        XlsxWriter.write(snapshot(), photos(), out)
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

    /** Один подрядчик, по строке на пропорцию — порядок якорей в drawing совпадает с [aspects]. */
    private fun snapshot() = JournalSnapshot(
        title = "Пропорции",
        contractors = listOf(SnapshotContractor(name = "Иванов")),
        fields = TestFixtures.legacyFields,
        days = listOf(
            SnapshotDay(
                dateLabel = "01.08",
                rows = aspects.map { (name, _) ->
                    SnapshotRow(cells = listOf(SnapshotCell(listOf(name, "кадр $name"), photoIds = listOf(name))))
                },
            ),
        ),
    )

    private fun photos() = object : PhotoBytesProvider {
        private val sizes = aspects.toMap()
        override fun open(photoId: String) =
            sizes.getValue(photoId).let { TestFixtures.jpegBytes(it.first, it.second) }.inputStream()

        override fun size(photoId: String) = sizes.getValue(photoId)
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

    private companion object {
        /** Ширина символа «0» в Calibri 11 — та же величина, что в [Widths]. */
        const val MDW = 7

        const val PX_PER_POINT = 96.0 / 72.0

        /** EMU на пиксель при 96 dpi: 914400 на дюйм / 96. */
        const val EMU_PER_PX = 9525.0
    }
}
