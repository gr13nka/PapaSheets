package ru.papasheets.domain

import ru.papasheets.data.db.entity.FieldDefEntity
import ru.papasheets.data.db.entity.RecordWithValues

private const val PREVIEW_LENGTH = 60

/**
 * Логика «Продолжить вчерашнее» (см. spec, форма записи): выбор среди вчерашних записей подрядчика
 * и их краткое представление для диалога-списка. Ноль зависимостей от Room/UI — вызывающая сторона
 * ([ru.papasheets.ui.record.RecordEditViewModel]) достаёт записи через [ru.papasheets.data.repo.RecordRepository]
 * и просто передаёт сюда результат.
 */
object ContinueYesterday {
    /** День перед датой формы — именно за него ищутся записи подрядчика. */
    fun yesterday(formDateEpochDay: Long): Long = formDateEpochDay - 1

    /** Единственная вчерашняя запись — подставляется в форму сразу, без диалога выбора. */
    fun singleCandidate(yesterdayRecords: List<RecordWithValues>): RecordWithValues? = yesterdayRecords.singleOrNull()

    /** Строка диалога выбора: метка записи + её содержание в одну строку, обрезанное до [PREVIEW_LENGTH] символов. */
    fun preview(record: RecordWithValues, fields: List<FieldDefEntity>): String {
        val display = RecordDisplay.of(record, fields)
        val text = display.secondaryLines.joinToString(" · ")
        val truncated = if (text.length > PREVIEW_LENGTH) text.take(PREVIEW_LENGTH) + "…" else text
        return if (display.primary.isNotBlank()) "${display.primary} — $truncated" else truncated
    }
}
