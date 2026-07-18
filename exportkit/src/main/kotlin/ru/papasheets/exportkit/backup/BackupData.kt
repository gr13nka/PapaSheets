package ru.papasheets.exportkit.backup

import kotlinx.serialization.Serializable

/**
 * Полный слепок пользовательских данных для .psbackup — плоские DTO, не Room-сущности: exportkit
 * ничего не знает о Room, а app при экспорте/импорте сам мапит [ru.papasheets.data.db.entity]-классы
 * в эти DTO и обратно (см. `BackupMappers.kt` в app). Поля — один в один с entity (UUID, epochDay,
 * millis, orderIndex, isArchived как есть), чтобы маппинг был тривиальным в обе стороны.
 */
@Serializable
data class BackupData(
    val journals: List<BackupJournal>,
    val contractors: List<BackupContractor>,
    val records: List<BackupRecord>,
    val photos: List<BackupPhoto>,
    val locationPresets: List<BackupLocationPreset>,
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
    val locationCode: String,
    val workText: String,
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

@Serializable
data class BackupLocationPreset(
    val id: String,
    val code: String,
    val orderIndex: Int,
)
