package ru.papasheets.exportkit.xlsx.read

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * Куда привязано фото: 0-based координаты ячейки-якоря (так их адресует DrawingML) и имя записи
 * архива с байтами. Перевод в строку/колонку листа — дело [SheetInterpreter] через
 * [ru.papasheets.exportkit.xlsx.MatrixSheetLayout].
 */
internal class DrawingAnchor(val column: Int, val row: Int, val mediaEntry: String)

/**
 * Разбор `xl/drawings/drawingN.xml`: якорь → ячейка → файл в `xl/media`.
 *
 * Без этой связки фото — просто набор картинок в архиве, и разложить их по записям нечем: у записи
 * нет собственной ссылки на фото, вся привязка живёт в якоре. В эталонном журнале так лежат 25
 * фотографий, все — на Ф-колонках своих подрядчиков.
 *
 * Поддерживается и `<xdr:oneCellAnchor>` (пишем мы, и так же пишет Google Sheets — эталон целиком
 * из них), и `<xdr:twoCellAnchor>`: Excel при вставке «в ячейку» ставит второй, и у него та же
 * начальная точка `<xdr:from>`, а конечная нам не нужна — важно, к какой записи фото относится,
 * а не какой прямоугольник оно занимает.
 *
 * Имя файла берётся не из якоря, а из `_rels`: в якоре стоит идентификатор связи (`r:embed="rId7"`),
 * а на какой файл он ведёт, знает только `drawingN.xml.rels`.
 */
internal class DrawingAnchorsParser(
    /** `rId` → имя записи архива, уже приведённое к абсолютному пути внутри пакета. */
    private val mediaByRelId: Map<String, String>,
) : DefaultHandler() {

    private val anchors = ArrayList<DrawingAnchor>()

    private var insideFrom = false
    private var column: Int? = null
    private var row: Int? = null
    private var mediaEntry: String? = null
    private var currentTag: String? = null
    private val buffer = StringBuilder()

    fun anchors(): List<DrawingAnchor> = anchors

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
        when (localName) {
            "oneCellAnchor", "twoCellAnchor", "absoluteAnchor" -> {
                column = null
                row = null
                mediaEntry = null
            }
            // <xdr:to> у twoCellAnchor содержит те же <col>/<row> — считаем только <xdr:from>.
            "from" -> insideFrom = true
            "col", "row" -> {
                currentTag = localName
                buffer.setLength(0)
            }
            "blip" -> {
                val embed = attributes?.getValue(
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                    "embed",
                ) ?: attributes?.getValue("r:embed")
                if (embed != null) mediaEntry = mediaByRelId[embed]
            }
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (insideFrom && currentTag != null) buffer.append(ch, start, length)
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        when (localName) {
            "col" -> if (insideFrom) column = buffer.toString().trim().toIntOrNull()
            "row" -> if (insideFrom) row = buffer.toString().trim().toIntOrNull()
            "from" -> insideFrom = false
            "oneCellAnchor", "twoCellAnchor", "absoluteAnchor" -> {
                val c = column
                val r = row
                val entry = mediaEntry
                // Якорь без координат или без картинки (фигура, диаграмма, подпись) молча пропускаем:
                // в чужом файле поверх листа может лежать что угодно, и это не повод не импортировать.
                if (c != null && r != null && entry != null) anchors.add(DrawingAnchor(c, r, entry))
            }
        }
        if (localName == "col" || localName == "row") currentTag = null
    }
}
