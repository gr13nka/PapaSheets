package ru.papasheets.exportkit.backup

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Stable identity shared by the Room migration and old-backup conversion. */
object LegacyTableStructure {
    fun id(kind: String, journalId: String, oldId: String): String = UUID.nameUUIDFromBytes(
        "papasheets/table-scope/v1/$kind/$journalId/$oldId".toByteArray(StandardCharsets.UTF_8),
    ).toString()

    fun recoveryJournal(ids: List<String>, createdAt: Long): BackupJournal {
        val date = Instant.ofEpochMilli(createdAt).atZone(ZoneOffset.UTC)
        return BackupJournal(id("recovery", "", ids.sorted().joinToString("|")), date.year, date.monthValue,
            "Previous setup", createdAt)
    }

    fun upgrade(data: BackupData): BackupData {
        val referencedFields = (data.recordValues.map { it.fieldId } + data.fieldPresets.map { it.fieldId } + data.fieldValueColors.map { it.fieldId }).toSet()
        val missing = BuiltInFields.ALL.filter { spec -> data.fieldDefs.none { it.id == spec.id } && (data.fieldDefs.isEmpty() || spec.id in referencedFields) }
        val fields = data.fieldDefs + missing.map { spec ->
            BackupFieldDef(spec.id, spec.title, spec.label, spec.orderIndex, false, false,
                spec.isRequired, spec.suggestFromHistory, spec.columnWidthDp, spec.maxLines,
                spec.showAtCompactLod, 0, fallbackDefinition = true)
        }
        val journals = data.journals.ifEmpty {
            if (data.contractors.isEmpty() && data.fieldDefs.isEmpty() && data.records.isEmpty()) emptyList()
            else listOf(recoveryJournal(data.contractors.map { it.id } + data.fieldDefs.map { it.id },
                (data.contractors.map { it.createdAt } + data.fieldDefs.map { it.createdAt }).maxOrNull() ?: 0))
        }
        val recordJournal = data.records.associate { it.id to it.journalId }
        return data.copy(
            journals = journals,
            contractors = journals.flatMap { journal -> data.contractors.map {
                it.copy(id = id("group", journal.id, it.id), journalId = journal.id)
            } },
            fieldDefs = journals.flatMap { journal -> fields.map {
                it.copy(id = id("field", journal.id, it.id), journalId = journal.id, isBuiltIn = false)
            } },
            fieldPresets = journals.flatMap { journal -> data.fieldPresets.map {
                it.copy(id = id("preset", journal.id, it.id), fieldId = id("field", journal.id, it.fieldId))
            } },
            fieldValueColors = journals.flatMap { journal -> data.fieldValueColors.map {
                it.copy(fieldId = id("field", journal.id, it.fieldId))
            } },
            records = data.records.map { it.copy(contractorId = id("group", it.journalId, it.contractorId)) },
            recordValues = data.recordValues.map {
                it.copy(fieldId = id("field", requireNotNull(recordJournal[it.recordId]), it.fieldId))
            },
        )
    }
}
