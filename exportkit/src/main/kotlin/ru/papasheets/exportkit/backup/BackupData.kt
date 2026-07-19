package ru.papasheets.exportkit.backup

import kotlinx.serialization.Serializable

/**
 * Полный слепок пользовательских данных для .psbackup — плоские DTO, не Room-сущности: exportkit
 * ничего не знает о Room, а app при экспорте/импорте сам мапит [ru.papasheets.data.db.entity]-классы
 * в эти DTO и обратно (см. `BackupMappers.kt` в app). Поля — один в один с entity (UUID, epochDay,
 * millis, orderIndex, isArchived как есть), чтобы маппинг был тривиальным в обе стороны.
 *
 * У списков, появившихся не в первой версии, есть значения по умолчанию, и это не украшение:
 * благодаря им data.json любой прошлой версии разбирается тем же кодом, без второго набора DTO и без
 * ветки «а если старый». Разобранное затем приводится к текущей форме — [BackupUpgrade].
 */
@Serializable
data class BackupData(
    val journals: List<BackupJournal>,
    val contractors: List<BackupContractor>,
    val records: List<BackupRecord>,
    val photos: List<BackupPhoto>,
    /**
     * Только для чтения бэкапов ≤ v2, где пресеты были привязкой к одной локации, а не к полю.
     * v3 их не пишет (всегда пусто): пресеты живут в [fieldPresets].
     */
    val locationPresets: List<BackupLocationPreset> = emptyList(),
    val fieldPresets: List<BackupFieldPreset> = emptyList(),
    val fieldDefs: List<BackupFieldDef> = emptyList(),
    val recordValues: List<BackupRecordValue> = emptyList(),
)

@Serializable
data class BackupJournal(
    val id: String,
    val year: Int,
    val month: Int,
    val title: String,
    val createdAt: Long,
)

@Serializable
data class BackupContractor(
    val id: String,
    val name: String,
    val shortName: String,
    val colorIndex: Int,
    val orderIndex: Int,
    val isArchived: Boolean,
    val createdAt: Long,
)

@Serializable
data class BackupRecord(
    val id: String,
    val journalId: String,
    val dateEpochDay: Long,
    val contractorId: String,
    /**
     * Только для чтения бэкапов v1, где содержимое записи ещё лежало в колонках `records`.
     * v2 их не пишет (всегда `null`): содержимое живёт в [BackupData.recordValues].
     * [BackupUpgrade] разворачивает их в значения и обнуляет здесь.
     */
    val locationCode: String? = null,
    /** См. [locationCode]: только для чтения v1, v2 пишет `null`. */
    val workText: String? = null,
    val photoId: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class BackupPhoto(
    val id: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val originUri: String?,
    val createdAt: Long,
)

/** См. [BackupData.locationPresets]: форма пресета в форматах ≤ v2, только для чтения старых файлов. */
@Serializable
data class BackupLocationPreset(
    val id: String,
    val code: String,
    val orderIndex: Int,
)

/** Готовое значение поля — поле в поле с `FieldPresetEntity`. */
@Serializable
data class BackupFieldPreset(
    val id: String,
    val fieldId: String,
    val code: String,
    val orderIndex: Int,
)

/** Определение поля записи — поле в поле с `FieldDefEntity`. Встроенные узнаются по id (см. [BuiltInFields]). */
@Serializable
data class BackupFieldDef(
    val id: String,
    val title: String,
    val label: String,
    val orderIndex: Int,
    val isArchived: Boolean,
    val isBuiltIn: Boolean,
    val isRequired: Boolean,
    val suggestFromHistory: Boolean,
    val columnWidthDp: Int,
    val maxLines: Int,
    val showAtCompactLod: Boolean,
    val createdAt: Long,
)

/**
 * Значение одного поля в одной записи. Пустых значений здесь не бывает — инвариант `RecordValueEntity`
 * («очистить поле» = удалить строку) распространяется и на бэкап, иначе при импорте «пусто» пришло бы
 * в двух неразличимых видах.
 */
@Serializable
data class BackupRecordValue(
    val recordId: String,
    val fieldId: String,
    val value: String,
)
