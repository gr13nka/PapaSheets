package ru.papasheets.exportkit.xlsx.read

import java.time.LocalDate

/**
 * Excel-serial → дата. В чужих файлах дата в колонке A лежит числом (`<v>46174.0</v>`), а не текстом:
 * так её пишут и Excel, и Google Sheets. Наш собственный экспорт, наоборот, пишет строку «01.06»
 * без года — оба варианта разбирает [DateCellParser].
 *
 * Точка отсчёта 1899-12-30, а не 1900-01-01, из-за известной ошибки Lotus 1-2-3, которую Excel
 * повторяет намеренно ради совместимости: 1900 год считается високосным и в нумерации существует
 * несуществующий день 1900-02-29 (serial 60). Поэтому до этого дня сдвиг на сутки другой, а сам
 * serial 60 не соответствует никакой реальной дате. Для журнала стройки это чистая экзотика —
 * но именно поэтому важно, чтобы мусорное число не превратилось молча в правдоподобную дату.
 */
internal object ExcelSerialDate {
    private val EPOCH: LocalDate = LocalDate.of(1899, 12, 30)

    /** Serial фальшивого дня 1900-02-29 — реальной даты за ним нет. */
    private const val FAKE_LEAP_DAY = 60

    /** Ниже этого serial действует сдвиг на сутки: дни до фальшивого 29 февраля 1900 года. */
    private const val FIRST_VALID = 1

    fun toLocalDate(serial: Double): LocalDate? {
        val days = serial.toInt()
        if (days < FIRST_VALID) return null
        if (days == FAKE_LEAP_DAY) return null
        // До фальшивого дня нумерация Excel опережает реальную на сутки, после — совпадает.
        val corrected = if (days < FAKE_LEAP_DAY) days + 1 else days
        return try {
            EPOCH.plusDays(corrected.toLong())
        } catch (e: java.time.DateTimeException) {
            null
        }
    }
}
