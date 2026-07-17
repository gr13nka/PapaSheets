package ru.papasheets.ui.record

import androidx.compose.runtime.saveable.listSaver
import java.time.LocalDate

/**
 * Режим формы записи: создание в конкретном журнале либо редактирование существующей записи.
 *
 * [Create.sessionId] отличает одно открытие формы от другого при одинаковых journalId/date —
 * без него повторное открытие «Новой записи» в тот же день возвращало бы тот же экземпляр
 * ViewModel из предыдущей (уже сохранённой) сессии вместе с её photoId, что упало бы на
 * уникальном индексе `records.photoId` при попытке сохранить вторую запись.
 *
 * [Create.contractorId] — предзаполнение подрядчика при создании из пустого слота матрицы (M3).
 */
sealed interface RecordSheetMode {
    data class Create(
        val journalId: String,
        val defaultDate: LocalDate,
        val sessionId: String,
        val contractorId: String? = null,
    ) : RecordSheetMode

    data class Edit(val recordId: String) : RecordSheetMode
}

/**
 * Переживает смерть процесса — иначе форма (и её ViewModel с temp-uri камеры) потерялась бы вместе
 * со свёрнутым приложением. Общий для всех экранов, открывающих [RecordSheet].
 */
val RecordSheetModeSaver = listSaver<RecordSheetMode?, Any>(
    save = { mode ->
        when (mode) {
            null -> emptyList()
            is RecordSheetMode.Create -> listOf(
                "create", mode.journalId, mode.defaultDate.toEpochDay(), mode.sessionId, mode.contractorId ?: "",
            )
            is RecordSheetMode.Edit -> listOf("edit", mode.recordId)
        }
    },
    restore = { saved ->
        if (saved.isEmpty()) {
            null
        } else when (saved[0]) {
            "create" -> RecordSheetMode.Create(
                journalId = saved[1] as String,
                defaultDate = LocalDate.ofEpochDay(saved[2] as Long),
                sessionId = saved[3] as String,
                contractorId = (saved[4] as String).ifEmpty { null },
            )
            "edit" -> RecordSheetMode.Edit(saved[1] as String)
            else -> null
        }
    },
)
