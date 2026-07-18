package ru.papasheets.exportkit.xlsx.read

import java.io.File
import java.time.LocalDate
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import ru.papasheets.exportkit.xlsx.MatrixSheetLayout

private const val WORKBOOK_PART = "xl/workbook.xml"

/**
 * Читает xlsx-журнал «матрица дата × подрядчик» — как наш собственный экспорт, так и таблицу,
 * которую вёл заказчик в Google Sheets (эталон — docs/reference/iyun-xlsx).
 *
 * Зеркало [ru.papasheets.exportkit.xlsx.XlsxWriter], и раскладку оба берут из общего
 * [MatrixSheetLayout] — иначе форматы разъехались бы при первой правке шага колонок.
 *
 * Принимает [File], а не поток: части xlsx лежат в произвольном порядке (media — после листа,
 * связи нужны раньше того, на что ссылаются), и однопроходный `ZipInputStream` заставил бы держать
 * весь архив в памяти. Скопировать `content://`-Uri во временный файл — забота вызывающей стороны.
 *
 * Всё, что чужой файл может нарушить, приводит к [XlsxFormatException] с текстом для пользователя.
 * Мусор внутри распознанной структуры, наоборот, к исключению не приводит: неизвестные фигуры
 * поверх листа, фото без байтов, пустые строки и полупустые группы — норма реального журнала,
 * и решение «импортировать или нет» принимает человек по предпросмотру, а не парсер.
 */
object XlsxReader {

    fun read(file: File): ParsedSheet {
        if (!file.isFile) throw XlsxFormatException("Файл не найден")
        return OpcPackage.open(file).use { pkg ->
            val workbook = readWorkbook(pkg)
            val sheetPart = resolveSheetPart(pkg, workbook)
            val sharedStrings = SharedStrings.readFrom(pkg)

            val sheetParser = SheetContentParser(sharedStrings)
            if (!pkg.parse(sheetPart, sheetParser)) {
                throw XlsxFormatException("В книге нет листа с данными")
            }
            val content = sheetParser.content()
            val layout = HeaderLayout.detect(content)
            val photos = readPhotoAnchors(pkg, sheetPart, layout)

            ParsedSheet(
                sheetName = workbook.sheetName,
                contractors = layout.contractorNames(content),
                fieldTitles = layout.fieldTitles(content),
                days = buildDays(content, layout, photos),
                source = file,
            )
        }
    }

    // --- части пакета -------------------------------------------------------------------------

    private class WorkbookInfo(val sheetName: String, val sheetRelId: String?)

    private fun readWorkbook(pkg: OpcPackage): WorkbookInfo {
        val handler = object : DefaultHandler() {
            var name: String? = null
            var relId: String? = null
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                // Интересует только первый <sheet>: журнал-месяц — всегда одна таблица на книгу.
                if (localName == "sheet" && name == null) {
                    name = attributes?.getValue("name")
                    relId = attributes?.getValue(
                        "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                        "id",
                    ) ?: attributes?.getValue("r:id")
                }
            }
        }
        if (!pkg.parse(WORKBOOK_PART, handler)) {
            throw XlsxFormatException("Файл не является xlsx-таблицей (нет xl/workbook.xml)")
        }
        return WorkbookInfo(handler.name.orEmpty(), handler.relId)
    }

    /**
     * Имя файла листа берётся через связи книги, а не угадывается: `sheet1.xml` не обязан быть
     * первым листом. Если связей нет (файл собран нестандартно), остаётся общепринятое имя.
     */
    private fun resolveSheetPart(pkg: OpcPackage, workbook: WorkbookInfo): String {
        val rels = Relationships.readFor(pkg, WORKBOOK_PART)
        val fromRels = workbook.sheetRelId?.let { rels.target(it) }
        return fromRels ?: "xl/worksheets/sheet1.xml"
    }

    /**
     * Фото листа: `лист → drawing → media`. Ключ результата — 1-based ячейка листа, к которой
     * привязан якорь, приведённая к колонке Ф своей группы: у Excel и Google Sheets картинка иногда
     * стоит не ровно на Ф, а на соседней колонке той же группы, и запись от этого не меняется.
     */
    private fun readPhotoAnchors(pkg: OpcPackage, sheetPart: String, layout: HeaderLayout): Map<PhotoKey, PhotoRef> {
        val drawingPart = Relationships.readFor(pkg, sheetPart).targetsOfType("/drawing").firstOrNull()
            ?: return emptyMap()
        val mediaByRelId = Relationships.readFor(pkg, drawingPart).targetsByIdOfType("/image")

        val parser = DrawingAnchorsParser(mediaByRelId)
        if (!pkg.parse(drawingPart, parser)) return emptyMap()

        val present = pkg.entryNames()
        val result = LinkedHashMap<PhotoKey, PhotoRef>()
        for (anchor in parser.anchors()) {
            val sheetRow = anchor.row + 1
            val sheetColumn = anchor.column + 1
            val contractorIndex = when (val role = MatrixSheetLayout.roleOf(sheetColumn, layout.groupWidth, layout.contractorCount)) {
                is MatrixSheetLayout.ColumnRole.Photo -> role.contractorIndex
                is MatrixSheetLayout.ColumnRole.Field -> role.contractorIndex
                else -> continue
            }
            // Первый якорь на ячейку выигрывает: в записи журнала фото одно, а лишние картинки
            // поверх той же ячейки — оформление, а не данные.
            result.putIfAbsent(
                PhotoKey(sheetRow, contractorIndex),
                PhotoRef(anchor.mediaEntry, isPresent = anchor.mediaEntry in present),
            )
        }
        return result
    }

    private data class PhotoKey(val sheetRow: Int, val contractorIndex: Int)

    // --- сборка записей -----------------------------------------------------------------------

    /**
     * Строки данных группируются в дни по значению колонки A. Дата повторяется в каждой строке дня
     * (так пишем и мы, и заказчик), поэтому смена значения = новый день; строка с пустой датой
     * считается продолжением предыдущего дня — в чужих таблицах дату у второй строки дня иногда
     * не дублируют.
     */
    private fun buildDays(content: SheetContent, layout: HeaderLayout, photos: Map<PhotoKey, PhotoRef>): List<ParsedDay> {
        val days = ArrayList<ParsedDay>()
        var currentRows = ArrayList<ParsedRow>()
        var currentLabel: String? = null
        var currentDate: LocalDate? = null

        for (sheetRow in content.rowNumbers()) {
            if (sheetRow < MatrixSheetLayout.FIRST_DATA_ROW) continue
            val row = buildRow(content, layout, photos, sheetRow)
            val dateCell = content.cells[sheetRow]?.get(MatrixSheetLayout.DATE_COLUMN)
            val parsedDate = DateCellParser.parse(dateCell)

            // Пустая строка без даты — просто дырка в листе (их сотни ниже данных), пропускаем.
            if (parsedDate == null && row.cells.all { it == null }) continue

            if (parsedDate != null && parsedDate.label != currentLabel) {
                if (currentLabel != null) days.add(ParsedDay(currentDate, currentLabel, currentRows))
                currentRows = ArrayList()
                currentLabel = parsedDate.label
                currentDate = parsedDate.date
            }
            if (currentLabel == null) {
                // Данные начались раньше первой распознанной даты — день без подписи, но с записями.
                currentLabel = ""
                currentDate = null
            }
            currentRows.add(row)
        }
        if (currentLabel != null) days.add(ParsedDay(currentDate, currentLabel, currentRows))
        return days
    }

    private fun buildRow(
        content: SheetContent,
        layout: HeaderLayout,
        photos: Map<PhotoKey, PhotoRef>,
        sheetRow: Int,
    ): ParsedRow {
        val fieldCount = MatrixSheetLayout.fieldCount(layout.groupWidth)
        val cells = (0 until layout.contractorCount).map { contractorIndex ->
            val values = (0 until fieldCount).map { fieldIndex ->
                content.textAt(sheetRow, MatrixSheetLayout.fieldColumn(contractorIndex, fieldIndex, layout.groupWidth))
            }
            val photo = photos[PhotoKey(sheetRow, contractorIndex)]
            val cell = ParsedCell(values, photo)
            if (cell.isBlank) null else cell
        }
        return ParsedRow(sheetRow, cells)
    }
}

/**
 * Ширина группы и число подрядчиков, вычитанные из шапки.
 *
 * Главный признак — merge-диапазоны строки 1: имя подрядчика растянуто ровно на свою группу, и это
 * единственное, что читается даже когда текста нет вовсе (в эталоне все строки вынесены в
 * sharedStrings, а сама часть из дампа вырезана). Запасной признак — метки «Ф» в строке 2: наш
 * собственный экспорт при пустом наборе полей групповых merge не пишет вовсе, потому что merge из
 * одной ячейки Excel считает повреждением файла.
 */
internal class HeaderLayout(val groupWidth: Int, val contractorCount: Int) {

    fun contractorNames(content: SheetContent): List<String> = (0 until contractorCount).map { index ->
        content.textAt(MatrixSheetLayout.CONTRACTOR_HEADER_ROW, MatrixSheetLayout.photoColumn(index, groupWidth))
    }

    /** Подписи полей берутся у первой группы: в матрице набор колонок у всех подрядчиков одинаковый. */
    fun fieldTitles(content: SheetContent): List<String> =
        (0 until MatrixSheetLayout.fieldCount(groupWidth)).map { fieldIndex ->
            content.textAt(MatrixSheetLayout.FIELD_HEADER_ROW, MatrixSheetLayout.fieldColumn(0, fieldIndex, groupWidth))
        }

    companion object {
        fun detect(content: SheetContent): HeaderLayout =
            fromMerges(content) ?: fromPhotoLabels(content)
                ?: throw XlsxFormatException(
                    "Это не журнал работ: в первой строке нет подрядчиков, разбитых по колонкам",
                )

        private fun fromMerges(content: SheetContent): HeaderLayout? {
            val groups = content.merges.mapNotNull { parseHorizontalHeaderMerge(it) }.sortedBy { it.first }
            if (groups.isEmpty()) return null
            // Ширина берётся по самой частой группе: у крайнего подрядчика merge иногда шире
            // остальных, а вся раскладка листа держится на едином шаге.
            val width = groups.groupingBy { it.second }.eachCount().maxByOrNull { it.value }?.key ?: return null
            return HeaderLayout(groupWidth = width, contractorCount = groups.size)
        }

        /** `B1:D1` → (первая колонка, ширина). Вертикальный `A1:A2` и любые merge вне строки 1 отбрасываются. */
        private fun parseHorizontalHeaderMerge(ref: String): Pair<Int, Int>? {
            val from = MatrixSheetLayout.parseCellRef(ref.substringBefore(':')) ?: return null
            val to = MatrixSheetLayout.parseCellRef(ref.substringAfter(':', "")) ?: return null
            if (from.row != MatrixSheetLayout.CONTRACTOR_HEADER_ROW || to.row != MatrixSheetLayout.CONTRACTOR_HEADER_ROW) return null
            if (from.column <= MatrixSheetLayout.DATE_COLUMN) return null
            val width = to.column - from.column + 1
            return if (width >= 1) from.column to width else null
        }

        private fun fromPhotoLabels(content: SheetContent): HeaderLayout? {
            val markers = content.cells[MatrixSheetLayout.FIELD_HEADER_ROW]
                ?.filterValues { it.text.trim() == MatrixSheetLayout.PHOTO_LABEL }
                ?.keys?.sorted()
                ?: return null
            if (markers.isEmpty()) return null
            val width = if (markers.size > 1) markers[1] - markers[0] else 1
            return HeaderLayout(groupWidth = width, contractorCount = markers.size)
        }
    }
}

/** Дата записи в том виде, в каком её удалось понять: подпись всегда, разобранная дата — если вышла. */
internal class ParsedDateCell(val label: String, val date: LocalDate?)

/**
 * Колонка A бывает и числом, и текстом, и это не вопрос вкуса: Excel и Google Sheets пишут дату
 * Excel-serial'ом (`46174.0`), а наш собственный экспорт — строкой «01.06» без года, потому что год
 * и так стоит в заголовке журнала. Читать нужно оба варианта, иначе половина реальных файлов
 * окажется без дат.
 */
internal object DateCellParser {
    fun parse(cell: SheetCellValue?): ParsedDateCell? {
        if (cell == null) return null
        val serial = cell.number
        if (serial != null) {
            val date = ExcelSerialDate.toLocalDate(serial) ?: return null
            return ParsedDateCell(label = date.toString(), date = date)
        }
        val text = cell.text.trim()
        if (text.isEmpty()) return null
        return ParsedDateCell(label = text, date = null)
    }
}
