package ru.papasheets.exportkit.csv

import java.io.OutputStream
import java.io.OutputStreamWriter
import ru.papasheets.exportkit.model.JournalSnapshot
import ru.papasheets.exportkit.xlsx.MatrixSheetLayout

private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

/**
 * Плоский CSV: одна строка = одна запись (в отличие от матрицы [ru.papasheets.exportkit.xlsx.XlsxWriter]
 * пустые слоты не создают строк). UTF-8 с BOM — иначе Excel на русской локали показывает кракозябры;
 * разделитель `;` — запятая в RU Excel это десятичный разделитель; экранирование по RFC4180.
 * Не закрывает [out] — так же, как [ru.papasheets.exportkit.xlsx.XlsxWriter], только флашит буфер.
 */
object CsvWriter {
    fun write(snapshot: JournalSnapshot, out: OutputStream) {
        out.write(UTF8_BOM)
        val writer = OutputStreamWriter(out, Charsets.UTF_8)
        // Заголовок собирается из тех же полей, что и данные, и экранируется тем же escape:
        // подпись поля задаёт пользователь, и `;` или `"` в ней сломали бы разбор первой строки.
        // Колонок под фото столько же, сколько слотов у записи, и заголовки у них фиксированные:
        // переменное число колонок сделало бы ширину строки зависящей от данных, а CSV читают
        // построчно, ожидая одинаковую шапку.
        val photoTitles = (1..MatrixSheetLayout.PHOTO_COLUMNS_PER_GROUP).map { "Фото $it" }
        writer.write(csvLine(listOf("Дата", "Подрядчик") + snapshot.fields.map { it.title } + photoTitles))
        for (day in snapshot.days) {
            for (row in day.rows) {
                row.cells.forEachIndexed { index, cell ->
                    if (cell != null) {
                        val contractor = snapshot.contractors[index].name
                        val photoFiles = (0 until MatrixSheetLayout.PHOTO_COLUMNS_PER_GROUP).map { slot ->
                            cell.photoIds.getOrNull(slot)?.let { "$it.jpg" } ?: ""
                        }
                        val values = snapshot.fields.indices.map { cell.values.getOrElse(it) { "" } }
                        writer.write(csvLine(listOf(day.dateLabel, contractor) + values + photoFiles))
                    }
                }
            }
        }
        writer.flush()
    }

    private fun csvLine(fields: List<String>): String =
        fields.joinToString(separator = ";", postfix = "\r\n") { escape(it) }

    private fun escape(field: String): String =
        if (field.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }
}
