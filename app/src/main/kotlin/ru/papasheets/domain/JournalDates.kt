package ru.papasheets.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Короткие русские месяцы для меток дат («17 июл»). Захардкожены, чтобы объект оставался чистым JVM. */
private val SHORT_MONTHS = arrayOf(
    "янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек",
)

private val NUMERIC_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM")

/**
 * Все форматы дат журнала в одном месте — раньше их было три независимых (матрица, форма записи,
 * экспорт), и они успели разъехаться по наличию года.
 *
 * Года нигде нет: журнал всегда охватывает один месяц, и год уже стоит в его заголовке («Июль 2026»),
 * который идёт и в имя листа xlsx, и в имя файла. Дублировать его в каждой строке — только тратить
 * ширину колонки, которой при печати и так мало.
 *
 * Без Android-зависимостей: [ru.papasheets.domain.buildGridModel] покрыт обычным JVM-тестом, а
 * ресурсы затащили бы в него Context.
 */
object JournalDates {
    /** «17.07» — колонка даты в экспорте (xlsx/CSV) и кнопка выбора даты в форме записи. */
    fun numeric(date: LocalDate): String = date.format(NUMERIC_FORMAT)

    /** «17 июл» — подпись даты в закреплённой колонке матрицы: словесный месяц читается быстрее цифр. */
    fun shortMonth(date: LocalDate): String = "${date.dayOfMonth} ${SHORT_MONTHS[date.monthValue - 1]}"

    /** «17» — компактная подпись колонки дат матрицы на сильном отдалении (LOD2, «картина месяца»). */
    fun dayNumber(date: LocalDate): String = date.dayOfMonth.toString()
}
