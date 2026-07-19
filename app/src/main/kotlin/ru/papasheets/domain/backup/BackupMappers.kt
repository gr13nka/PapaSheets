package ru.papasheets.domain.backup

import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.data.db.entity.FieldDefEntity
import ru.papasheets.data.db.entity.FieldPresetEntity
import ru.papasheets.data.db.entity.JournalEntity
import ru.papasheets.data.db.entity.RecordEntity
import ru.papasheets.data.db.entity.RecordValueEntity
import ru.papasheets.exportkit.backup.BackupContractor
import ru.papasheets.exportkit.backup.BackupFieldDef
import ru.papasheets.exportkit.backup.BackupFieldPreset
import ru.papasheets.exportkit.backup.BackupJournal
import ru.papasheets.exportkit.backup.BackupPhoto
import ru.papasheets.exportkit.backup.BackupRecord
import ru.papasheets.exportkit.backup.BackupRecordValue
import ru.papasheets.photos.PhotoMeta

/**
 * Взаимно-однозначные мапперы между Room-сущностями и бэкап-DTO exportkit — поле в поле, без
 * трансформаций (бэкап хранит все поля entity как есть, см. spec). Единственное место, которое
 * знает оба набора классов — и [BackupInteractor], и [ImportInteractor] проходят через него, так что
 * список полей не может разъехаться между экспортом и импортом.
 */

fun JournalEntity.toBackup() = BackupJournal(id, year, month, title, createdAt)
fun BackupJournal.toEntity() = JournalEntity(id, year, month, title, createdAt)

fun ContractorEntity.toBackup() = BackupContractor(id, name, shortName, colorIndex, orderIndex, isArchived, createdAt)
fun BackupContractor.toEntity() = ContractorEntity(id, name, shortName, colorIndex, orderIndex, isArchived, createdAt)

/**
 * `BackupRecord.locationCode`/`workText` живут только в формате v1 и здесь не участвуют ни в одну
 * сторону: при экспорте остаются `null`, при импорте их содержимое к этому моменту уже развёрнуто
 * в `recordValues` — этим занимается `BackupUpgrade` внутри `BackupReader`. В таблице `records`
 * соответствующих колонок нет с v3.
 */
fun RecordEntity.toBackup() = BackupRecord(id, journalId, dateEpochDay, contractorId, photoId = photoId, createdAt = createdAt, updatedAt = updatedAt)
fun BackupRecord.toEntity() = RecordEntity(id, journalId, dateEpochDay, contractorId, photoId, createdAt, updatedAt)

fun PhotoMeta.toBackup() = BackupPhoto(id, width, height, sizeBytes, originUri, createdAt)
fun BackupPhoto.toMeta() = PhotoMeta(id, width, height, sizeBytes, originUri, createdAt)

/**
 * `BackupLocationPreset` парой сюда не входит: пресеты без поля бывают только в файлах ≤ v2, и к
 * этому моменту `BackupUpgrade` уже привязал их к «Локации» и переложил в `fieldPresets`.
 */
fun FieldPresetEntity.toBackup() = BackupFieldPreset(id, fieldId, code, orderIndex)
fun BackupFieldPreset.toEntity() = FieldPresetEntity(id, fieldId, code, orderIndex)

fun FieldDefEntity.toBackup() = BackupFieldDef(
    id, title, label, orderIndex, isArchived, isBuiltIn, isRequired,
    suggestFromHistory, columnWidthDp, maxLines, showAtCompactLod, createdAt,
)

fun BackupFieldDef.toEntity() = FieldDefEntity(
    id, title, label, orderIndex, isArchived, isBuiltIn, isRequired,
    suggestFromHistory, columnWidthDp, maxLines, showAtCompactLod, createdAt,
)

fun RecordValueEntity.toBackup() = BackupRecordValue(recordId, fieldId, value)
fun BackupRecordValue.toEntity() = RecordValueEntity(recordId, fieldId, value)
