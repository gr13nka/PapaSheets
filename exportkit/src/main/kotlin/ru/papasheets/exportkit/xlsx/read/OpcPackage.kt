package ru.papasheets.exportkit.xlsx.read

import java.io.File
import java.io.IOException
import java.io.StringReader
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/**
 * Доступ к частям xlsx как к именованным XML-документам.
 *
 * Открывает архив [ZipFile], а не [java.util.zip.ZipInputStream], потому что порядок частей внутри
 * xlsx не задан: `xl/media` лежит после листа, а связи (`_rels`) читать нужно раньше того, на что
 * они ссылаются. Однопроходный поток заставил бы либо буферизовать весь архив, либо читать его
 * дважды — [ZipFile] даёт произвольный доступ по центральному каталогу.
 */
internal class OpcPackage private constructor(private val zip: ZipFile) : AutoCloseable {

    companion object {
        fun open(file: File): OpcPackage = try {
            OpcPackage(ZipFile(file))
        } catch (e: ZipException) {
            throw XlsxFormatException("Файл не является xlsx-таблицей (не удалось открыть архив)", e)
        } catch (e: IOException) {
            throw XlsxFormatException("Файл не удалось прочитать", e)
        }
    }

    fun hasPart(name: String): Boolean = zip.getEntry(name) != null

    /**
     * Разбирает часть архива SAX-обработчиком. `null`, если части нет: в OPC необязательных частей
     * много (sharedStrings, drawing), и их отсутствие — не ошибка формата.
     */
    fun parse(name: String, handler: DefaultHandler): Boolean {
        val entry = zip.getEntry(name) ?: return false
        try {
            zip.getInputStream(entry).use { stream ->
                newSecureParser().parse(InputSource(stream), handler)
            }
        } catch (e: SAXException) {
            throw XlsxFormatException("Повреждённая разметка внутри файла (${name})", e)
        } catch (e: IOException) {
            throw XlsxFormatException("Файл оборван или повреждён (${name})", e)
        }
        return true
    }

    /** Имена всех записей архива — нужно, чтобы отличить объявленное фото от реально вложенного. */
    fun entryNames(): Set<String> = zip.entries().asSequence().mapTo(HashSet()) { it.name }

    override fun close() {
        zip.close()
    }
}

/**
 * SAX-разбор с выключенными внешними сущностями.
 *
 * Файл приходит извне — его выбирает пользователь в системном диалоге, и подсунуть вместо журнала
 * xml с `<!ENTITY xxe SYSTEM "file:///...">` может кто угодно. С настройками по умолчанию парсер
 * такую сущность резолвит и утащит содержимое чужого файла (или уйдёт в сеть, или подвесится на
 * «billion laughs»). Поэтому doctype запрещён целиком: в xlsx его быть не должно, а запрет на
 * объявление — единственная защита, которую нельзя обойти вложенностью.
 *
 * Взят [SAXParser], а не StAX: `javax.xml.stream` на Android отсутствует, `XmlPullParser` есть
 * только на Android, и лишь SAX работает и в JVM-тестах, и на устройстве.
 */
private fun newSecureParser(): SAXParser = try {
    val factory = SAXParserFactory.newInstance()
    factory.isNamespaceAware = true
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    val parser = factory.newSAXParser()
    // Подстраховка на случай парсера, который проглотил бы doctype: любая внешняя ссылка
    // резолвится в пустоту вместо похода в файловую систему или сеть.
    parser.xmlReader.entityResolver = EntityResolver { _, _ -> InputSource(StringReader("")) }
    parser
} catch (e: ParserConfigurationException) {
    throw XlsxFormatException("XML-парсер не поддерживает безопасный режим разбора", e)
} catch (e: SAXException) {
    throw XlsxFormatException("XML-парсер не поддерживает безопасный режим разбора", e)
}
