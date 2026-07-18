package ru.papasheets.exportkit.csv

import java.io.OutputStream
import java.io.OutputStreamWriter
import ru.papasheets.exportkit.model.JournalSnapshot

private const val CSV_HEADER = "Дата;Подрядчик;Локация;Вид работ;Фото"
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
        writer.write(CSV_HEADER)
        writer.write("\r\n")
        for (day in snapshot.days) {
            for (row in day.rows) {
                row.cells.forEachIndexed { index, cell ->
                    if (cell != null) {
                        val contractor = snapshot.contractors[index].name
                        val photoFile = cell.photoId?.let { "$it.jpg" } ?: ""
                        writer.write(csvLine(day.dateLabel, contractor, cell.locationCode, cell.workText, photoFile))
                    }
                }
            }
        }
        writer.flush()
    }

    private fun csvLine(vararg fields: String): String =
        fields.joinToString(separator = ";", postfix = "\r\n") { escape(it) }

    private fun escape(field: String): String =
        if (field.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }
}
