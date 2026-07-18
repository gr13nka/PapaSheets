package ru.papasheets.exportkit.xlsx

import ru.papasheets.exportkit.model.JournalSnapshot

// Ширины колонок группы подрядчика (символьные единицы Excel) — как в эталонном журнале
// (docs/reference/iyun-xlsx/xl/worksheets/sheet1.xml, <cols>): Ф≈5.75, Л≈7.75, ВИД РАБОТ≈50.75.
// Колонка A немного шире эталонной (5.75), чтобы полная дата «17.07.2026» не резалась соседней ячейкой.
private const val COL_DATE_WIDTH = 10.5
private const val COL_PHOTO_WIDTH = 5.75
private const val COL_LOCATION_WIDTH = 7.75
private const val COL_WORK_WIDTH = 50.75

/**
 * Высота строки с хотя бы одним фото, в пунктах. Эталонный журнал держит фото как крошечную
 * иконку в строке по умолчанию (15pt) — то же самое было бы нечитаемо; здесь фото сознательно
 * крупнее (см. отчёт M6), высоту строки под них выбираем сами.
 */
internal const val PHOTO_ROW_HEIGHT_PT = 90.0

/**
 * Рендерит `xl/worksheets/sheet1.xml`: шапка (имена подрядчиков — merge по 3 колонки в строке 1,
 * Ф/Л/ВИД РАБОТ — в строке 2), freeze-панель B3, данные (одна физическая строка листа = одна
 * [ru.papasheets.exportkit.model.SnapshotRow]; колонка Ф всегда пустым текстом — туда, если есть,
 * ложится фото поверх ячейки). Не трогает ZIP и байты фото — только геометрию и текст листа;
 * [hasDrawing] решает, ссылаться ли на drawing1.xml и утолщать ли строки с фото (вариант «без фото»
 * игнорирует photoId в ячейках полностью, даже если он проставлен).
 */
internal object SheetXml {
    fun build(snapshot: JournalSnapshot, hasDrawing: Boolean): String {
        val contractorCount = snapshot.contractors.size
        val colCount = 1 + 3 * contractorCount
        val lastRow = 2 + snapshot.days.sumOf { it.rows.size }

        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append(
                """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """ +
                    """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""",
            )
            append("""<dimension ref="A1:${columnLetter(colCount)}$lastRow"/>""")
            append("<sheetViews><sheetView workbookViewId=\"0\">")
            append("""<pane xSplit="1" ySplit="2" topLeftCell="B3" state="frozen"/>""")
            append("</sheetView></sheetViews>")
            append("""<sheetFormatPr defaultRowHeight="15.0"/>""")
            appendCols(contractorCount)
            append("<sheetData>")
            appendHeaderRows(snapshot)
            appendDataRows(snapshot, hasDrawing)
            append("</sheetData>")
            appendMergeCells(contractorCount)
            if (hasDrawing) append("""<drawing r:id="rId1"/>""")
            append("</worksheet>")
        }
    }

    private fun StringBuilder.appendCols(contractorCount: Int) {
        append("<cols>")
        append(col(1, COL_DATE_WIDTH))
        var col = 2
        repeat(contractorCount) {
            append(col(col, COL_PHOTO_WIDTH))
            append(col(col + 1, COL_LOCATION_WIDTH))
            append(col(col + 2, COL_WORK_WIDTH))
            col += 3
        }
        append("</cols>")
    }

    private fun col(index: Int, width: Double): String =
        """<col min="$index" max="$index" width="$width" customWidth="1"/>"""

    private fun StringBuilder.appendHeaderRows(snapshot: JournalSnapshot) {
        // Строка 1: «ДАТА» (merge A1:A2) + имя подрядчика на группу (merge 3 колонки, см. appendMergeCells).
        append("<row r=\"1\">")
        append(cell(ref(1, 1), STYLE_HEADER_BOLD, "ДАТА"))
        var col = 2
        for (contractor in snapshot.contractors) {
            append(cell(ref(col, 1), STYLE_HEADER_BOLD, contractor.name))
            append(emptyCell(ref(col + 1, 1), STYLE_DEFAULT))
            append(emptyCell(ref(col + 2, 1), STYLE_DEFAULT))
            col += 3
        }
        append("</row>")

        // Строка 2: Ф / Л / ВИД РАБОТ на группу; A2 пустая — вторая половина merge A1:A2.
        append("<row r=\"2\" ht=\"19.5\" customHeight=\"1\">")
        append(emptyCell(ref(1, 2), STYLE_DEFAULT))
        col = 2
        repeat(snapshot.contractors.size) {
            append(cell(ref(col, 2), STYLE_HEADER_LABEL, "Ф"))
            append(cell(ref(col + 1, 2), STYLE_HEADER_LABEL, "Л"))
            append(cell(ref(col + 2, 2), STYLE_HEADER_LABEL, "ВИД РАБОТ"))
            col += 3
        }
        append("</row>")
    }

    private fun StringBuilder.appendDataRows(snapshot: JournalSnapshot, hasDrawing: Boolean) {
        var rowNum = 3
        for (day in snapshot.days) {
            for (row in day.rows) {
                val rowHasPhoto = hasDrawing && row.cells.any { it?.photoId != null }
                append(if (rowHasPhoto) "<row r=\"$rowNum\" ht=\"$PHOTO_ROW_HEIGHT_PT\" customHeight=\"1\">" else "<row r=\"$rowNum\">")
                append(cell(ref(1, rowNum), STYLE_DEFAULT, day.dateLabel))
                var col = 2
                for (value in row.cells) {
                    if (value == null) {
                        append(emptyCell(ref(col, rowNum), STYLE_DEFAULT))
                        append(emptyCell(ref(col + 1, rowNum), STYLE_DEFAULT))
                        append(emptyCell(ref(col + 2, rowNum), STYLE_WRAP))
                    } else {
                        // Ф — только якорь фото (см. drawing), без собственного текста.
                        append(emptyCell(ref(col, rowNum), STYLE_DEFAULT))
                        append(cell(ref(col + 1, rowNum), STYLE_DEFAULT, value.locationCode))
                        append(cell(ref(col + 2, rowNum), STYLE_WRAP, value.workText))
                    }
                    col += 3
                }
                append("</row>")
                rowNum++
            }
        }
    }

    private fun StringBuilder.appendMergeCells(contractorCount: Int) {
        append("""<mergeCells count="${1 + contractorCount}">""")
        append("""<mergeCell ref="A1:A2"/>""")
        var col = 2
        repeat(contractorCount) {
            append("""<mergeCell ref="${ref(col, 1)}:${ref(col + 2, 1)}"/>""")
            col += 3
        }
        append("</mergeCells>")
    }

    private fun cell(ref: String, style: Int, text: String): String =
        """<c r="$ref" s="$style" t="inlineStr"><is><t xml:space="preserve">${Xml.escape(text)}</t></is></c>"""

    private fun emptyCell(ref: String, style: Int): String = """<c r="$ref" s="$style"/>"""

    private fun ref(col: Int, row: Int): String = "${columnLetter(col)}$row"

    /** 1-based номер колонки → буквенная ссылка Excel (1→A, 27→AA, …). */
    private fun columnLetter(oneIndexed: Int): String {
        var n = oneIndexed
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, 'A' + rem)
            n = (n - 1) / 26
        }
        return sb.toString()
    }
}
