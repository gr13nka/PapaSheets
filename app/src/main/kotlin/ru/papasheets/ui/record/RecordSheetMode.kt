package ru.papasheets.ui.record

import java.time.LocalDate

/**
 * Режим формы записи: создание в конкретном журнале либо редактирование существующей записи.
 *
 * [Create.sessionId] отличает одно открытие формы от другого при одинаковых journalId/date —
 * без него повторное открытие «Новой записи» в тот же день возвращало бы тот же экземпляр
 * ViewModel из предыдущей (уже сохранённой) сессии вместе с её photoId, что упало бы на
 * уникальном индексе `records.photoId` при попытке сохранить вторую запись.
 */
sealed interface RecordSheetMode {
    data class Create(val journalId: String, val defaultDate: LocalDate, val sessionId: String) : RecordSheetMode
    data class Edit(val recordId: String) : RecordSheetMode
}
