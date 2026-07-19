package ru.papasheets.exportkit.xlsx

import ru.papasheets.exportkit.model.JournalSnapshot

// Ширины двух служебных колонок в символьных единицах Excel; ширины подколонок полей приходят из
// их dp через [Widths]. Колонка A шире эталонной (docs/reference/iyun-xlsx, <cols>: 5.75) с запасом
// под дату.
//
// Колонка Ф эталонную ширину, наоборот, переросла, и намеренно: в эталоне фото — иконка в строке
// высотой по умолчанию, а здесь оно сознательно крупное (см. [PHOTO_BOX_PT]). Ширина считается от
// стороны фото-квадрата, а не подбирается на глаз, — иначе картинка накрывала бы первую колонку поля.
private const val COL_DATE_WIDTH = 10.5
private val COL_PHOTO_WIDTH = Widths.ptToChars(PHOTO_BOX_PT)

/**
 * Рендерит `xl/worksheets/sheet1.xml`: шапка (имена подрядчиков — merge по всей группе в строке 1,
 * Ф и подписи полей — в строке 2), freeze-панель B3, данные (одна физическая строка листа = одна
 * [ru.papasheets.exportkit.model.SnapshotRow]; колонка Ф всегда пустым текстом — туда, если есть,
 * ложится фото поверх ячейки) и настройки печати. Не трогает ZIP и байты фото — только геометрию и
 * текст листа; [hasDrawing] решает, ссылаться ли на drawing1.xml и задавать ли высоту строк с фото
 * (вариант «без фото» игнорирует photoId в ячейках полностью, даже если он проставлен).
 *
 * Где что лежит — в [MatrixSheetLayout]: те же формулы читает
 * [ru.papasheets.exportkit.xlsx.read.XlsxReader], поэтому задавать шаг колонок здесь заново нельзя.
 * Ширина группы считается один раз (`groupCols`) и передаётся дальше по вызовам.
 */
internal object SheetXml {
    fun build(snapshot: JournalSnapshot, hasDrawing: Boolean): String {
        val contractorCount = snapshot.contractors.size
        val groupCols = MatrixSheetLayout.groupWidth(snapshot.fields.size)
        val colCount = MatrixSheetLayout.totalColumns(contractorCount, groupCols)
        val lastRow = MatrixSheetLayout.FIELD_HEADER_ROW + snapshot.days.sumOf { it.rows.size }

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
        append(col(MatrixSheetLayout.DATE_COLUMN, COL_DATE_WIDTH))
        snapshot.contractors.indices.forEach { contractorIndex ->
            append(col(MatrixSheetLayout.photoColumn(contractorIndex, groupCols), COL_PHOTO_WIDTH))
            snapshot.fields.forEachIndexed { index, field ->
                append(col(MatrixSheetLayout.fieldColumn(contractorIndex, index, groupCols), field.widthChars))
            }
        }
        append("</cols>")
    }

    private fun col(index: Int, width: Double): String =
        """<col min="$index" max="$index" width="$width" customWidth="1"/>"""

    private fun StringBuilder.appendHeaderRows(snapshot: JournalSnapshot, groupCols: Int) {
        // Строка 1: «ДАТА» (merge A1:A2) + имя подрядчика на группу (merge всей группы, см. appendMergeCells).
        val contractorRow = MatrixSheetLayout.CONTRACTOR_HEADER_ROW
        append("<row r=\"$contractorRow\">")
        append(cell(ref(MatrixSheetLayout.DATE_COLUMN, contractorRow), STYLE_HEADER_BOLD, "ДАТА"))
        snapshot.contractors.forEachIndexed { contractorIndex, contractor ->
            append(cell(ref(MatrixSheetLayout.photoColumn(contractorIndex, groupCols), contractorRow), STYLE_HEADER_BOLD, contractor.name))
            for (index in snapshot.fields.indices) {
                append(emptyCell(ref(MatrixSheetLayout.fieldColumn(contractorIndex, index, groupCols), contractorRow), STYLE_DEFAULT))
            }
        }
        append("</row>")

        // Строка 2: Ф и подписи полей на группу; A2 пустая — вторая половина merge A1:A2.
        val fieldRow = MatrixSheetLayout.FIELD_HEADER_ROW
        append("<row r=\"$fieldRow\" ht=\"19.5\" customHeight=\"1\">")
        append(emptyCell(ref(MatrixSheetLayout.DATE_COLUMN, fieldRow), STYLE_DEFAULT))
        snapshot.contractors.indices.forEach { contractorIndex ->
            append(cell(ref(MatrixSheetLayout.photoColumn(contractorIndex, groupCols), fieldRow), STYLE_HEADER_LABEL, MatrixSheetLayout.PHOTO_LABEL))
            snapshot.fields.forEachIndexed { index, field ->
                append(cell(ref(MatrixSheetLayout.fieldColumn(contractorIndex, index, groupCols), fieldRow), STYLE_HEADER_LABEL, field.title))
            }
        }
        append("</row>")
    }

    private fun StringBuilder.appendDataRows(snapshot: JournalSnapshot, hasDrawing: Boolean, groupCols: Int) {
        val fields = snapshot.fields
        var rowNum = MatrixSheetLayout.FIRST_DATA_ROW
        for (day in snapshot.days) {
            for (row in day.rows) {
                val rowHasPhoto = hasDrawing && row.cells.any { it?.photoId != null }
                if (rowHasPhoto) {
                    append("<row r=\"$rowNum\" ht=\"${RowHeight.forPhotoRow(row, fields)}\" customHeight=\"1\">")
                } else {
                    // Без customHeight Excel сам подгоняет высоту под перенесённый текст — точнее нашей оценки.
                    append("<row r=\"$rowNum\">")
                }
                append(cell(ref(MatrixSheetLayout.DATE_COLUMN, rowNum), STYLE_DEFAULT, day.dateLabel))
                row.cells.forEachIndexed { contractorIndex, cellValue ->
                    // Ф — только якорь фото (см. drawing), без собственного текста.
                    append(emptyCell(ref(MatrixSheetLayout.photoColumn(contractorIndex, groupCols), rowNum), STYLE_DEFAULT))
                    fields.forEachIndexed { index, field ->
                        val style = if (field.wrap) STYLE_WRAP else STYLE_DEFAULT
                        val cellRef = ref(MatrixSheetLayout.fieldColumn(contractorIndex, index, groupCols), rowNum)
                        val text = cellValue?.values?.getOrElse(index) { "" }
                        append(if (text == null) emptyCell(cellRef, style) else cell(cellRef, style, text))
                    }
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
        val headerRow = MatrixSheetLayout.CONTRACTOR_HEADER_ROW
        append("""<mergeCells count="${1 + groupMerges}">""")
        append(
            """<mergeCell ref="${ref(MatrixSheetLayout.DATE_COLUMN, headerRow)}:""" +
                """${ref(MatrixSheetLayout.DATE_COLUMN, MatrixSheetLayout.FIELD_HEADER_ROW)}"/>""",
        )
        repeat(groupMerges) { contractorIndex ->
            val first = MatrixSheetLayout.photoColumn(contractorIndex, groupCols)
            append("""<mergeCell ref="${ref(first, headerRow)}:${ref(first + groupCols - 1, headerRow)}"/>""")
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

    private fun columnLetter(oneIndexed: Int): String = MatrixSheetLayout.columnLetter(oneIndexed)
}
