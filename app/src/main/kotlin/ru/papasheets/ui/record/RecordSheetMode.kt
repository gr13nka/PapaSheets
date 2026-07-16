package ru.papasheets.ui.record

import java.time.LocalDate

/** Режим формы записи: создание в конкретном журнале либо редактирование существующей записи. */
sealed interface RecordSheetMode {
    data class Create(val journalId: String, val defaultDate: LocalDate) : RecordSheetMode
    data class Edit(val recordId: String) : RecordSheetMode
}
