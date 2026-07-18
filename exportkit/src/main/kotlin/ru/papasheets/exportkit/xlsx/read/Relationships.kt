package ru.papasheets.exportkit.xlsx.read

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * Связи одной части пакета (`_rels/<часть>.rels`): `rId` → путь к другой части.
 *
 * В OPC части не ссылаются друг на друга по именам файлов — только через идентификаторы связей.
 * Поэтому «какой лист главный» и «в каком файле лежит вот это фото» без разбора `_rels` не узнать,
 * а угадывать по именам нельзя: `sheet1.xml` не обязан быть первым листом книги.
 */
internal class Relationships(
    private val targetsById: Map<String, String>,
    private val typesById: Map<String, String>,
) {

    /** Абсолютный путь части внутри пакета по `rId`, или `null`, если связи нет. */
    fun target(relId: String): String? = targetsById[relId]

    /**
     * Цели связей данного типа в порядке объявления. Тип — последний сегмент URI схемы
     * (`…/relationships/drawing` → `"/drawing"`): по нему часть и опознаётся, имя файла ни о чём
     * не говорит.
     */
    fun targetsOfType(typeSuffix: String): List<String> =
        targetsById.filterKeys { typesById[it]?.endsWith(typeSuffix) == true }.values.toList()

    /** `rId` → путь, только для связей данного типа. */
    fun targetsByIdOfType(typeSuffix: String): Map<String, String> =
        targetsById.filterKeys { typesById[it]?.endsWith(typeSuffix) == true }

    companion object {
        /** Путь к части со связями для части [partName] — `xl/worksheets/_rels/sheet1.xml.rels`. */
        fun relsPartFor(partName: String): String {
            val folder = partName.substringBeforeLast('/', "")
            val file = partName.substringAfterLast('/')
            return if (folder.isEmpty()) "_rels/$file.rels" else "$folder/_rels/$file.rels"
        }

        /** Пустые связи, если части `.rels` нет — законная ситуация для части без исходящих ссылок. */
        fun readFor(pkg: OpcPackage, partName: String): Relationships {
            val handler = Handler(baseFolder = partName.substringBeforeLast('/', ""))
            pkg.parse(relsPartFor(partName), handler)
            return Relationships(handler.targets, handler.types)
        }
    }

    private class Handler(private val baseFolder: String) : DefaultHandler() {
        val targets = LinkedHashMap<String, String>()
        val types = LinkedHashMap<String, String>()

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            if (localName != "Relationship") return
            val id = attributes?.getValue("Id") ?: return
            val target = attributes.getValue("Target") ?: return
            // Внешние ссылки (картинка по URL) ведут наружу пакета — внутри архива их байтов нет.
            if (attributes.getValue("TargetMode") == "External") return
            targets[id] = resolve(target)
            attributes.getValue("Type")?.let { types[id] = it }
        }

        /** Цель связи задана относительно папки своей части: `../media/x.jpg` из `xl/drawings` → `xl/media/x.jpg`. */
        private fun resolve(target: String): String {
            if (target.startsWith("/")) return target.removePrefix("/")
            val segments = ArrayList<String>()
            if (baseFolder.isNotEmpty()) segments.addAll(baseFolder.split('/'))
            for (part in target.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
                    else -> segments.add(part)
                }
            }
            return segments.joinToString("/")
        }
    }
}
