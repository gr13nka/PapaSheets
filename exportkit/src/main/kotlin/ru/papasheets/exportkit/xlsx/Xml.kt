package ru.papasheets.exportkit.xlsx

/**
 * Экранирование текста для XML 1.0: спецсимволы разметки (`& < > " '`) плюс вырезание управляющих
 * символов (кроме tab/LF/CR), которые считаются невалидными в XML 1.0 и ломают строгие парсеры
 * (Excel — один из них, в отличие от Google Sheets).
 */
internal object Xml {
    fun escape(text: String): String {
        val sb = StringBuilder(text.length + 16)
        for (ch in text) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                '\t', '\n', '\r' -> sb.append(ch)
                else -> if (ch.code >= 0x20 && ch.code != 0x7F) sb.append(ch)
            }
        }
        return sb.toString()
    }
}
