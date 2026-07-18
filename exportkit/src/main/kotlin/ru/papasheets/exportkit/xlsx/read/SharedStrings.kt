package ru.papasheets.exportkit.xlsx.read

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

private const val SHARED_STRINGS_PART = "xl/sharedStrings.xml"

/**
 * Таблица общих строк (`xl/sharedStrings.xml`).
 *
 * Наш экспорт пишет текст прямо в ячейку (`t="inlineStr"`), а Excel и Google Sheets выносят его
 * в общую таблицу и оставляют в ячейке индекс (`t="s"`, `<v>17</v>`) — в эталонном журнале так
 * лежат все 176 строк без исключения. Читать чужие файлы, не разрешая эти индексы, невозможно:
 * без таблицы у листа не будет ни имён подрядчиков, ни описаний работ.
 *
 * Одна строка таблицы (`<si>`) может быть разбита на куски-«раны» (`<r><t>…</t></r>`), если внутри
 * менялось оформление; куски склеиваются — форматирование нам не нужно, текст нужен целиком.
 */
internal class SharedStrings private constructor(private val values: List<String>) {

    /**
     * Строка по индексу. Ссылка за границы таблицы даёт `""`, а не исключение: части может не быть
     * вовсе (так вырезан наш эталон), и терять из-за этого геометрию листа и фото было бы хуже,
     * чем показать записи без текста — что видно в предпросмотре перед записью в БД.
     */
    fun get(index: Int): String = values.getOrElse(index) { "" }

    val size: Int get() = values.size

    companion object {
        fun readFrom(pkg: OpcPackage): SharedStrings {
            val handler = Handler()
            pkg.parse(SHARED_STRINGS_PART, handler)
            return SharedStrings(handler.values)
        }
    }

    private class Handler : DefaultHandler() {
        val values = ArrayList<String>()
        private val current = StringBuilder()
        private var insideItem = false
        private var insideText = false

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            when (localName) {
                "si" -> {
                    insideItem = true
                    current.setLength(0)
                }
                "t" -> insideText = true
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (insideItem && insideText) current.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (localName) {
                "si" -> {
                    values.add(current.toString())
                    insideItem = false
                }
                "t" -> insideText = false
            }
        }
    }
}
