package ru.papasheets.exportkit.xlsx.read

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import ru.papasheets.exportkit.xlsx.MatrixSheetLayout

/**
 * Сырое содержимое листа: ячейки по координатам и объявленные merge-диапазоны — до всякой
 * интерпретации в терминах подрядчиков и полей. Отделено от [SheetInterpreter] намеренно: разбор
 * XML и понимание раскладки — разные решения, и путать их значило бы чинить формат в парсере.
 */
internal class SheetContent(
    /** Ячейки: строка листа → колонка → значение. Разреженно, как в файле. */
    val cells: Map<Int, Map<Int, SheetCellValue>>,
    /** Ссылки merge-диапазонов вида `B1:D1` в порядке объявления. */
    val merges: List<String>,
) {
    fun textAt(row: Int, column: Int): String = cells[row]?.get(column)?.text.orEmpty()

    fun rowNumbers(): List<Int> = cells.keys.sorted()
}

/**
 * Значение ячейки. Текст и число хранятся раздельно, потому что колонка A может содержать и то, и
 * другое: Excel пишет дату числом-serial, наш экспорт — строкой «01.06» (см. [DateCellParser]).
 */
internal class SheetCellValue(val text: String, val number: Double?)

/**
 * SAX-разбор `xl/worksheets/sheetN.xml`.
 *
 * Поддерживает оба способа хранения текста, потому что встречаются оба: `t="s"` с индексом в
 * [SharedStrings] (так пишут Excel и Google Sheets — весь эталонный журнал) и `t="inlineStr"` с
 * текстом внутри `<is><t>` (так пишем мы сами). Ячейка без `t` — число; для колонки A это
 * Excel-serial даты.
 */
internal class SheetContentParser(private val sharedStrings: SharedStrings) : DefaultHandler() {
    private val cells = LinkedHashMap<Int, MutableMap<Int, SheetCellValue>>()
    private val merges = ArrayList<String>()

    private var currentRow = 0
    private var currentColumn = 0
    private var currentType: String? = null
    private var insideValue = false
    private var insideInlineText = false
    private val buffer = StringBuilder()

    fun content(): SheetContent = SheetContent(cells, merges)

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
        when (localName) {
            "row" -> currentRow = attributes?.getValue("r")?.toIntOrNull() ?: (currentRow + 1)
            "c" -> {
                val ref = attributes?.getValue("r")
                // Ячейка без ссылки допустима: тогда она просто следующая в строке.
                currentColumn = ref?.let { MatrixSheetLayout.parseCellRef(it)?.column } ?: (currentColumn + 1)
                currentType = attributes?.getValue("t")
                buffer.setLength(0)
            }
            "v" -> {
                insideValue = true
                buffer.setLength(0)
            }
            "t" -> {
                insideInlineText = true
                buffer.setLength(0)
            }
            "mergeCell" -> attributes?.getValue("ref")?.let { merges.add(it) }
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (insideValue || insideInlineText) buffer.append(ch, start, length)
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        when (localName) {
            "v" -> {
                insideValue = false
                val raw = buffer.toString()
                val value = when (currentType) {
                    "s" -> SheetCellValue(raw.trim().toIntOrNull()?.let(sharedStrings::get).orEmpty(), null)
                    // t="str" — результат формулы, уже текстом.
                    "str" -> SheetCellValue(raw, null)
                    else -> SheetCellValue(raw, raw.trim().toDoubleOrNull())
                }
                put(value)
            }
            "t" -> {
                insideInlineText = false
                // <t> встречается и внутри <is> (инлайновый текст ячейки), и внутри sharedStrings;
                // здесь мы всегда на листе, так что это ячейка.
                if (currentType == "inlineStr") put(SheetCellValue(buffer.toString(), null))
            }
        }
    }

    private fun put(value: SheetCellValue) {
        if (currentRow <= 0 || currentColumn <= 0) return
        val row = cells.getOrPut(currentRow) { LinkedHashMap() }
        // Инлайновая строка может прийти несколькими <t>-кусками — склеиваем, а не затираем.
        val existing = row[currentColumn]
        row[currentColumn] = if (existing == null) value else SheetCellValue(existing.text + value.text, value.number)
    }
}
