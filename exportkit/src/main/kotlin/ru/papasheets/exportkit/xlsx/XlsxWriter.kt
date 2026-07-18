package ru.papasheets.exportkit.xlsx

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import ru.papasheets.exportkit.model.JournalSnapshot
import ru.papasheets.exportkit.model.PhotoBytesProvider

/** Отступ от высоты фото-строки до самого фото (см. [PHOTO_ROW_HEIGHT_PT]) — небольшое поле сверху/снизу для читаемости. */
private const val PHOTO_DISPLAY_HEIGHT_PT = PHOTO_ROW_HEIGHT_PT - 8.0

/**
 * Стриминговая генерация xlsx в формате «матрица дата×подрядчик» (эталон — реальный рабочий журнал,
 * см. docs/reference/iyun-xlsx): ZIP собирается через [ZipOutputStream], XML-части — небольшие строки
 * в памяти (сотни строк листа — это десятки-сотни КБ текста, не проблема), фото копируются побайтово
 * из [PhotoBytesProvider] без перекодирования — единственное, что могло бы раздуть память на сотнях фото.
 *
 * `photos == null` → лист без фото: та же раскладка данных, но без `xl/drawings` и `xl/media`.
 * Заканчивает запись ([ZipOutputStream.finish]), но не закрывает [out] — закрытие остаётся за
 * вызывающей стороной (она открыла поток, ей и решать, когда его отпустить).
 */
object XlsxWriter {
    fun write(snapshot: JournalSnapshot, photos: PhotoBytesProvider?, out: OutputStream) {
        val anchors = if (photos != null) collectAnchors(snapshot, photos) else emptyList()
        val hasDrawing = anchors.isNotEmpty()
        val zip = ZipOutputStream(out)

        putEntry(zip, "[Content_Types].xml", ContentTypesXml.build(hasDrawing))
        putEntry(zip, "_rels/.rels", RelsXml.root())
        putEntry(zip, "xl/workbook.xml", workbookXml(snapshot.title))
        putEntry(zip, "xl/_rels/workbook.xml.rels", RelsXml.workbook())
        putEntry(zip, "xl/styles.xml", StylesXml.build())
        putEntry(zip, "xl/worksheets/sheet1.xml", SheetXml.build(snapshot, hasDrawing))

        if (hasDrawing && photos != null) {
            putEntry(zip, "xl/worksheets/_rels/sheet1.xml.rels", RelsXml.sheet())
            putEntry(zip, "xl/drawings/drawing1.xml", DrawingXml.build(anchors))
            putEntry(zip, "xl/drawings/_rels/drawing1.xml.rels", RelsXml.drawing(anchors))
            for (anchor in anchors) {
                zip.putNextEntry(ZipEntry("xl/media/${anchor.photoId}.jpg"))
                photos.open(anchor.photoId).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }

        zip.finish()
    }

    /**
     * Один проход по снимку: якорь на Ф-ячейку каждой записи с фото, размер — из реальных пикселей
     * фото ([PhotoBytesProvider.size]) с сохранением пропорций. Координаты якоря (0-based, как их
     * адресует DrawingML) считает [MatrixSheetLayout] — тот же, по которому [SheetXml] раскладывает
     * колонки, иначе фото оказались бы в чужих.
     */
    private fun collectAnchors(snapshot: JournalSnapshot, photos: PhotoBytesProvider): List<PhotoAnchor> {
        val anchors = ArrayList<PhotoAnchor>()
        val cy = Emu.fromPoints(PHOTO_DISPLAY_HEIGHT_PT)
        val groupCols = MatrixSheetLayout.groupWidth(snapshot.fields.size)
        var dataRowIndex = 0
        for (day in snapshot.days) {
            for (row in day.rows) {
                row.cells.forEachIndexed { colIndex, cellValue ->
                    val photoId = cellValue?.photoId
                    if (photoId != null) {
                        val (width, height) = photos.size(photoId)
                        val cx = Math.round(cy * (width.toDouble() / height.toDouble()))
                        anchors += PhotoAnchor(
                            col = MatrixSheetLayout.anchorColumn(colIndex, groupCols),
                            row = MatrixSheetLayout.anchorRow(dataRowIndex),
                            rId = "rId${anchors.size + 1}",
                            extCx = cx,
                            extCy = cy,
                            photoId = photoId,
                        )
                    }
                }
                dataRowIndex++
            }
        }
        return anchors
    }

    /**
     * `xl/workbook.xml`. Кроме единственного листа объявляет `_xlnm.Print_Titles` — встроенное имя,
     * которым Excel повторяет шапку (строки 1–2) на каждой печатной странице; иначе со второй
     * страницы читатель видит колонки без подрядчиков. Порядок элементов задан схемой:
     * `definedNames` идут после `sheets`.
     */
    private fun workbookXml(title: String): String {
        val sheetName = sanitizeSheetName(title)
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """ +
            """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
            """<sheets><sheet name="${Xml.escape(sheetName)}" sheetId="1" r:id="rId1"/></sheets>""" +
            """<definedNames><definedName name="_xlnm.Print_Titles" localSheetId="0">""" +
            Xml.escape("'${sheetName.replace("'", "''")}'!${'$'}1:${'$'}2") +
            """</definedName></definedNames>""" +
            """</workbook>"""
    }

    /** Имена листов Excel: максимум 31 символ, без `\/?*[]:`. Заголовок журнала («Июль 2026») и так укладывается — это подстраховка. */
    private fun sanitizeSheetName(title: String): String {
        val cleaned = title.map { if (it in "\\/?*[]:") ' ' else it }.joinToString("")
        return cleaned.take(31).ifBlank { "Журнал" }
    }

    private fun putEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
