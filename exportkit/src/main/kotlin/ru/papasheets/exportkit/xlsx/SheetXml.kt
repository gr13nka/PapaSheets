package ru.papasheets.exportkit.xlsx

import ru.papasheets.exportkit.model.JournalSnapshot

// Ширины двух служебных колонок в символьных единицах Excel; ширины подколонок полей приходят из
// их dp через [Widths]. Колонка A шире эталонной (docs/reference/iyun-xlsx, <cols>: 5.75) с запасом
// под дату.
//
// Ширина колонки Ф равна ВЫСОТЕ строки с фото, а не стороне фото-квадрата: фото должно помещаться
// в свою ячейку с полем, и по горизонтали ему нужно ровно то же поле [PHOTO_PADDING_PT], которое у
// него уже есть по вертикали ([PHOTO_ROW_HEIGHT_PT] = [PHOTO_BOX_PT] + поле). Иначе Google Sheets на
// телефоне не рисует картинку вовсе — замеры и разбор в docs/evolution.md, «xlsx». Именно поэтому
// вертикальные фото работали всегда (их высота 56pt в строке 64pt), а горизонтальные пропадали:
// их длинная сторона идёт поперёк колонки, а колонка поля не имела. Инвариант — тест `PhotoFitTest`.
private const val COL_DATE_WIDTH = 10.5
private val COL_PHOTO_WIDTH = Widths.ptToChars(PHOTO_ROW_HEIGHT_PT)

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
        val layout = MatrixSheetLayout.forWriting(snapshot.fields.size)
        val colCount = layout.totalColumns(contractorCount)
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
            appendCols(snapshot, layout)
            append("<sheetData>")
            appendHeaderRows(snapshot, layout)
            appendDataRows(snapshot, hasDrawing, layout)
            append("</sheetData>")
            appendMergeCells(contractorCount, layout)
            appendPrintSetup()
            if (hasDrawing) append("""<drawing r:id="rId1"/>""")
            append("</worksheet>")
        }
    }

    private fun StringBuilder.appendCols(snapshot: JournalSnapshot, layout: MatrixSheetLayout) {
        append("<cols>")
        append(col(MatrixSheetLayout.DATE_COLUMN, COL_DATE_WIDTH))
        snapshot.contractors.indices.forEach { contractorIndex ->
            repeat(layout.photoColumns) { slot ->
                append(col(layout.photoColumn(contractorIndex, slot), COL_PHOTO_WIDTH))
            }
            snapshot.fields.forEachIndexed { index, field ->
                append(col(layout.fieldColumn(contractorIndex, index), field.widthChars))
            }
        }
        append("</cols>")
    }

    private fun col(index: Int, width: Double): String =
        """<col min="$index" max="$index" width="$width" customWidth="1"/>"""

    private fun StringBuilder.appendHeaderRows(snapshot: JournalSnapshot, layout: MatrixSheetLayout) {
        // Строка 1: «ДАТА» (merge A1:A2) + имя подрядчика на группу (merge всей группы, см. appendMergeCells).
        // Имя стоит в первой ячейке группы, остальные её колонки — пустые: их накрывает merge.
        val contractorRow = MatrixSheetLayout.CONTRACTOR_HEADER_ROW
        append("<row r=\"$contractorRow\">")
        append(cell(ref(MatrixSheetLayout.DATE_COLUMN, contractorRow), STYLE_HEADER_BOLD, "ДАТА"))
        snapshot.contractors.forEachIndexed { contractorIndex, contractor ->
            append(cell(ref(layout.photoColumn(contractorIndex, 0), contractorRow), STYLE_HEADER_BOLD, contractor.name))
            for (slot in 1 until layout.photoColumns) {
                append(emptyCell(ref(layout.photoColumn(contractorIndex, slot), contractorRow), STYLE_DEFAULT))
            }
            for (index in snapshot.fields.indices) {
                append(emptyCell(ref(layout.fieldColumn(contractorIndex, index), contractorRow), STYLE_DEFAULT))
            }
        }
        append("</row>")

        // Строка 2: Ф и подписи полей на группу; A2 пустая — вторая половина merge A1:A2.
        // Обе колонки фото подписаны одинаково: читатель узнаёт их число по длине серии «Ф»,
        // а различать слоты по подписи («Ф1»/«Ф2») значило бы сломать чтение старых файлов.
        val fieldRow = MatrixSheetLayout.FIELD_HEADER_ROW
        append("<row r=\"$fieldRow\" ht=\"19.5\" customHeight=\"1\">")
        append(emptyCell(ref(MatrixSheetLayout.DATE_COLUMN, fieldRow), STYLE_DEFAULT))
        snapshot.contractors.indices.forEach { contractorIndex ->
            repeat(layout.photoColumns) { slot ->
                append(cell(ref(layout.photoColumn(contractorIndex, slot), fieldRow), STYLE_HEADER_LABEL, MatrixSheetLayout.PHOTO_LABEL))
            }
            snapshot.fields.forEachIndexed { index, field ->
                append(cell(ref(layout.fieldColumn(contractorIndex, index), fieldRow), STYLE_HEADER_LABEL, field.title))
            }
        }
        append("</row>")
    }

    private fun StringBuilder.appendDataRows(snapshot: JournalSnapshot, hasDrawing: Boolean, layout: MatrixSheetLayout) {
        val fields = snapshot.fields
        var rowNum = MatrixSheetLayout.FIRST_DATA_ROW
        for (day in snapshot.days) {
            for (row in day.rows) {
                val rowHasPhoto = hasDrawing && row.cells.any { it?.photoIds?.isNotEmpty() == true }
                if (rowHasPhoto) {
                    append("<row r=\"$rowNum\" ht=\"${RowHeight.forPhotoRow(row, fields)}\" customHeight=\"1\">")
                } else {
                    // Без customHeight Excel сам подгоняет высоту под перенесённый текст — точнее нашей оценки.
                    append("<row r=\"$rowNum\">")
                }
                append(cell(ref(MatrixSheetLayout.DATE_COLUMN, rowNum), STYLE_DEFAULT, day.dateLabel))
                row.cells.forEachIndexed { contractorIndex, cellValue ->
                    // Ф — только якоря фото (см. drawing), без собственного текста.
                    repeat(layout.photoColumns) { slot ->
                        append(emptyCell(ref(layout.photoColumn(contractorIndex, slot), rowNum), STYLE_DEFAULT))
                    }
                    fields.forEachIndexed { index, field ->
                        val style = if (field.wrap) STYLE_WRAP else STYLE_DEFAULT
                        val cellRef = ref(layout.fieldColumn(contractorIndex, index), rowNum)
                        val text = cellValue?.values?.getOrElse(index) { "" }
                        append(if (text == null) emptyCell(cellRef, style) else cell(cellRef, style, text))
                    }
                }
                append("</row>")
                rowNum++
            }
        }
    }

    private fun StringBuilder.appendMergeCells(contractorCount: Int, layout: MatrixSheetLayout) {
        // Merge из одной ячейки Excel считает повреждением файла, а count обязан совпадать с числом
        // реально выведенных элементов. С двумя колонками Ф группа не бывает уже двух колонок, так
        // что вырожденный случай остался в прошлом, — но проверка сохранена: она держится за ширину
        // группы, а не за веру в то, что PHOTO_COLUMNS_PER_GROUP больше единицы.
        val groupMerges = if (layout.groupWidth > 1) contractorCount else 0
        val headerRow = MatrixSheetLayout.CONTRACTOR_HEADER_ROW
        append("""<mergeCells count="${1 + groupMerges}">""")
        append(
            """<mergeCell ref="${ref(MatrixSheetLayout.DATE_COLUMN, headerRow)}:""" +
                """${ref(MatrixSheetLayout.DATE_COLUMN, MatrixSheetLayout.FIELD_HEADER_ROW)}"/>""",
        )
        repeat(groupMerges) { contractorIndex ->
            val first = layout.photoColumn(contractorIndex, 0)
            append("""<mergeCell ref="${ref(first, headerRow)}:${ref(first + layout.groupWidth - 1, headerRow)}"/>""")
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
