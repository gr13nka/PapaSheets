package ru.papasheets.domain.backup

import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.data.db.entity.JournalEntity
import ru.papasheets.data.db.entity.LocationPresetEntity
import ru.papasheets.data.db.entity.RecordEntity
import ru.papasheets.exportkit.backup.BackupContractor
import ru.papasheets.exportkit.backup.BackupJournal
import ru.papasheets.exportkit.backup.BackupLocationPreset
import ru.papasheets.exportkit.backup.BackupPhoto
import ru.papasheets.exportkit.backup.BackupRecord
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

fun RecordEntity.toBackup() = BackupRecord(id, journalId, dateEpochDay, contractorId, locationCode, workText, photoId, createdAt, updatedAt)
fun BackupRecord.toEntity() = RecordEntity(id, journalId, dateEpochDay, contractorId, locationCode, workText, photoId, createdAt, updatedAt)

fun PhotoMeta.toBackup() = BackupPhoto(id, width, height, sizeBytes, originUri, createdAt)
fun BackupPhoto.toMeta() = PhotoMeta(id, width, height, sizeBytes, originUri, createdAt)

fun LocationPresetEntity.toBackup() = BackupLocationPreset(id, code, orderIndex)
fun BackupLocationPreset.toEntity() = LocationPresetEntity(id, code, orderIndex)
