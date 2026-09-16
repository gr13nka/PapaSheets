package ru.papasheets.data.repo

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.combine
import ru.papasheets.data.db.AppDatabase
import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.data.db.entity.FieldDefEntity
import ru.papasheets.data.db.entity.JournalEntity

data class TableStructure(val groups: List<ContractorEntity>, val fields: List<FieldDefEntity>)

/** A structure is a value to copy, never a shared mutable template. */
class TableStructureRepository(private val db: AppDatabase) {
    fun observe(journalId: String) = combine(
        db.contractorDao().observeForJournal(journalId), db.fieldDefDao().observeForJournal(journalId),
    ) { groups, fields -> TableStructure(groups, fields) }

    suspend fun create(
        title: String, year: Int, month: Int, sourceId: String?,
        defaultGroupName: String, defaultDescription: String,
    ): String = db.withTransaction {
        require(title.isNotBlank())
        require(month in 1..12)
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        if (sourceId != null) requireNotNull(db.journalDao().getById(sourceId))
        db.journalDao().insert(JournalEntity(id, year, month, title.trim(), now))
        if (sourceId == null) {
            db.contractorDao().insert(ContractorEntity(UUID.randomUUID().toString(), defaultGroupName,
                defaultGroupName, 0, 0, createdAt = now, journalId = id))
            db.fieldDefDao().insert(FieldDefEntity(UUID.randomUUID().toString(), defaultDescription,
                defaultDescription, 0, false, false, false, true, 168, 0, true, now, id))
        } else {
            db.contractorDao().getForJournal(sourceId).forEach { group ->
                db.contractorDao().insert(group.copy(id = UUID.randomUUID().toString(), journalId = id, createdAt = now))
            }
            val colors = db.fieldValueColorDao().getAll().groupBy { it.fieldId }
            db.fieldDefDao().getForJournal(sourceId).forEach { field ->
                val fieldId = UUID.randomUUID().toString()
                db.fieldDefDao().insert(field.copy(id = fieldId, journalId = id, createdAt = now, isBuiltIn = false))
                db.fieldPresetDao().presetsFor(field.id).forEach { preset ->
                    db.fieldPresetDao().insert(preset.copy(id = UUID.randomUUID().toString(), fieldId = fieldId))
                }
                colors[field.id].orEmpty().forEach { color ->
                    db.fieldValueColorDao().upsert(color.copy(fieldId = fieldId))
                }
            }
        }
        id
    }
}
