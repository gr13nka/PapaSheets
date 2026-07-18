package ru.papasheets.exportkit.xlsx

import ru.papasheets.exportkit.model.JournalSnapshot

// Ширины двух служебных колонок (символьные единицы Excel) — как в эталонном журнале
// (docs/reference/iyun-xlsx/xl/worksheets/sheet1.xml, <cols>): Ф≈5.75. Ширины подколонок полей
// приходят из их dp через [Widths]. Колонка A шире эталонной (5.75) с запасом под дату.
private const val COL_DATE_WIDTH = 10.5
private const val COL_PHOTO_WIDTH = 5.75

/**
 * Рендерит `xl/worksheets/sheet1.xml`: шапка (имена подрядчиков — merge по всей группе в строке 1,
 * Ф и подписи полей — в строке 2), freeze-панель B3, данные (одна физическая строка листа = одна
 * [ru.papasheets.exportkit.model.SnapshotRow]; колонка Ф всегда пустым текстом — туда, если есть,
 * ложится фото поверх ячейки) и настройки печати. Не трогает ZIP и байты фото — только геометрию и
 * текст листа; [hasDrawing] решает, ссылаться ли на drawing1.xml и задавать ли высоту строк с фото
 * (вариант «без фото» игнорирует photoId в ячейках полностью, даже если он проставлен).
 *
 * Группа подрядчика = Ф плюс по колонке на каждое поле снимка; ширина группы считается один раз
 * (`groupCols`) и передаётся дальше, чтобы шаг колонок нельзя было задать в двух местах по-разному.
 */
internal object SheetXml {
    fun build(snapshot: JournalSnapshot, hasDrawing: Boolean): String {
        val contractorCount = snapshot.contractors.size
        val groupCols = 1 + snapshot.fields.size
        val colCount = 1 + groupCols * contractorCount
        val lastRow = 2 + snapshot.days.sumOf { it.rows.size }

        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append(
                """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """ +
                    """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""",
            )
            // Порядок элементов внутри <worksheet> задан схемой SpreadsheetML, и Excel отвергает файл
            // при его нарушении: sheetPr → dimension → sheetViews → sheetFormatPr → cols → sheetData →
            // mergeCells → printOptions → pageMargins → pageSetup → drawing.
            append("""<sheetPr><pageSetUpPr fitToPage="1"/></sheetPr>""")
            append("""<dimension ref="A1:${columnLetter(colCount)}$lastRow"/>""")
            append("<sheetViews><sheetView workbookViewId=\"0\">")
            append("""<pane xSplit="1" ySplit="2" topLeftCell="B3" state="frozen"/>""")
            append("</sheetView></sheetViews>")
            append("""<sheetFormatPr defaultRowHeight="15.0"/>""")
            appendCols(snapshot, groupCols)
            append("<sheetData>")
            appendHeaderRows(snapshot, groupCols)
            appendDataRows(snapshot, hasDrawing, groupCols)
            append("</sheetData>")
            appendMergeCells(contractorCount, groupCols)
            appendPrintSetup()
            if (hasDrawing) append("""<drawing r:id="rId1"/>""")
            append("</worksheet>")
        }
    }

    private fun StringBuilder.appendCols(snapshot: JournalSnapshot, groupCols: Int) {
        append("<cols>")
        append(col(1, COL_DATE_WIDTH))
        var col = 2
        repeat(snapshot.contractors.size) {
            append(col(col, COL_PHOTO_WIDTH))
            snapshot.fields.forEachIndexed { index, field -> append(col(col + 1 + index, field.widthChars)) }
            col += groupCols
        }
        append("</cols>")
    }

    private fun col(index: Int, width: Double): String =
        """<col min="$index" max="$index" width="$width" customWidth="1"/>"""

    private fun StringBuilder.appendHeaderRows(snapshot: JournalSnapshot, groupCols: Int) {
        // Строка 1: «ДАТА» (merge A1:A2) + имя подрядчика на группу (merge всей группы, см. appendMergeCells).
        append("<row r=\"1\">")
        append(cell(ref(1, 1), STYLE_HEADER_BOLD, "ДАТА"))
        var col = 2
        for (contractor in snapshot.contractors) {
            append(cell(ref(col, 1), STYLE_HEADER_BOLD, contractor.name))
            for (index in snapshot.fields.indices) append(emptyCell(ref(col + 1 + index, 1), STYLE_DEFAULT))
            col += groupCols
        }
        append("</row>")

        // Строка 2: Ф и подписи полей на группу; A2 пустая — вторая половина merge A1:A2.
        append("<row r=\"2\" ht=\"19.5\" customHeight=\"1\">")
        append(emptyCell(ref(1, 2), STYLE_DEFAULT))
        col = 2
        repeat(snapshot.contractors.size) {
            append(cell(ref(col, 2), STYLE_HEADER_LABEL, "Ф"))
            snapshot.fields.forEachIndexed { index, field ->
                append(cell(ref(col + 1 + index, 2), STYLE_HEADER_LABEL, field.title))
            }
            col += groupCols
        }
        append("</row>")
    }

    private fun StringBuilder.appendDataRows(snapshot: JournalSnapshot, hasDrawing: Boolean, groupCols: Int) {
        val fields = snapshot.fields
        var rowNum = 3
        for (day in snapshot.days) {
            for (row in day.rows) {
                val rowHasPhoto = hasDrawing && row.cells.any { it?.photoId != null }
                if (rowHasPhoto) {
                    append("<row r=\"$rowNum\" ht=\"${RowHeight.forPhotoRow(row, fields)}\" customHeight=\"1\">")
                } else {
                    // Без customHeight Excel сам подгоняет высоту под перенесённый текст — точнее нашей оценки.
                    append("<row r=\"$rowNum\">")
                }
                append(cell(ref(1, rowNum), STYLE_DEFAULT, day.dateLabel))
                var col = 2
                for (cellValue in row.cells) {
                    // Ф — только якорь фото (см. drawing), без собственного текста.
                    append(emptyCell(ref(col, rowNum), STYLE_DEFAULT))
                    fields.forEachIndexed { index, field ->
                        val style = if (field.wrap) STYLE_WRAP else STYLE_DEFAULT
                        val cellRef = ref(col + 1 + index, rowNum)
                        val text = cellValue?.values?.getOrElse(index) { "" }
                        append(if (text == null) emptyCell(cellRef, style) else cell(cellRef, style, text))
                    }
                    col += groupCols
                }
                append("</row>")
                rowNum++
            }
        }
    }

    private fun StringBuilder.appendMergeCells(contractorCount: Int, groupCols: Int) {
        // Merge из одной ячейки Excel считает повреждением файла, а count обязан совпадать с числом
        // реально выведенных элементов — поэтому при пустом наборе полей (вся группа = одна колонка Ф)
        // групповые merge не выводятся вовсе.
        val groupMerges = if (groupCols > 1) contractorCount else 0
        append("""<mergeCells count="${1 + groupMerges}">""")
        append("""<mergeCell ref="A1:A2"/>""")
        var col = 2
        repeat(groupMerges) {
            append("""<mergeCell ref="${ref(col, 1)}:${ref(col + groupCols - 1, 1)}"/>""")
            col += groupCols
        }
        append("</mergeCells>")
    }

    /**
     * Печать: альбомная ориентация и вписывание по ширине (в высоту — сколько выйдет страниц).
     * `fitToWidth` без `<sheetPr><pageSetUpPr fitToPage="1"/></sheetPr>` Excel молча игнорирует,
     * печатая в масштабе 100%. Повтор шапки на каждой странице задаётся не здесь, а defined name
     * `_xlnm.Print_Titles` в `xl/workbook.xml` (см. [XlsxWriter]).
     */
    private fun StringBuilder.appendPrintSetup() {
        append("""<printOptions/>""")
        append("""<pageMargins left="0.25" right="0.25" top="0.5" bottom="0.5" header="0.3" footer="0.3"/>""")
        append("""<pageSetup orientation="landscape" fitToWidth="1" fitToHeight="0"/>""")
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
