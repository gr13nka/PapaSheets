package ru.papasheets.domain.backup

import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.data.db.entity.FieldDefEntity
import ru.papasheets.data.db.entity.JournalEntity
import ru.papasheets.data.db.entity.LocationPresetEntity
import ru.papasheets.data.db.entity.RecordEntity
import ru.papasheets.data.db.entity.RecordValueEntity
import ru.papasheets.exportkit.backup.BackupContractor
import ru.papasheets.exportkit.backup.BackupFieldDef
import ru.papasheets.exportkit.backup.BackupJournal
import ru.papasheets.exportkit.backup.BackupLocationPreset
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
 * Замороженные `locationCode`/`workText` в обе стороны игнорируются: содержимое записи ездит в
 * `record_values`. При экспорте в них пишется `null` (формат v2 их не заполняет), при импорте — `""`,
 * ровно как их заводит `RecordRepository.createRecord`. Значения бэкапа v1 к этому моменту уже
 * развёрнуты в `recordValues` — этим занимается `BackupUpgrade` внутри `BackupReader`.
 */
fun RecordEntity.toBackup() = BackupRecord(id, journalId, dateEpochDay, contractorId, null, null, photoId, createdAt, updatedAt)
fun BackupRecord.toEntity() = RecordEntity(id, journalId, dateEpochDay, contractorId, "", "", photoId, createdAt, updatedAt)

fun PhotoMeta.toBackup() = BackupPhoto(id, width, height, sizeBytes, originUri, createdAt)
fun BackupPhoto.toMeta() = PhotoMeta(id, width, height, sizeBytes, originUri, createdAt)

fun LocationPresetEntity.toBackup() = BackupLocationPreset(id, code, orderIndex)
fun BackupLocationPreset.toEntity() = LocationPresetEntity(id, code, orderIndex)

fun FieldDefEntity.toBackup() = BackupFieldDef(
    id, key, title, label, orderIndex, isArchived, isBuiltIn, isRequired,
    suggestFromHistory, columnWidthDp, maxLines, showAtCompactLod, createdAt,
)

fun BackupFieldDef.toEntity() = FieldDefEntity(
    id, key, title, label, orderIndex, isArchived, isBuiltIn, isRequired,
    suggestFromHistory, columnWidthDp, maxLines, showAtCompactLod, createdAt,
)

fun RecordValueEntity.toBackup() = BackupRecordValue(recordId, fieldId, value)
fun BackupRecordValue.toEntity() = RecordValueEntity(recordId, fieldId, value)
