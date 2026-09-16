package ru.papasheets.exportkit.backup

/** A full backup must describe one consistent ownership graph before any database writes. */
internal fun BackupData.validateOwnership() {
    val journalIds = journals.map { it.id }.toSet()
    val groupsById = contractors.associateBy { it.id }
    val fieldsById = fieldDefs.associateBy { it.id }
    val recordsById = records.associateBy { it.id }
    require(journalIds.size == journals.size && groupsById.size == contractors.size && fieldsById.size == fieldDefs.size && recordsById.size == records.size)
    require(contractors.all { it.journalId in journalIds })
    require(fieldDefs.all { it.journalId in journalIds })
    require(records.all { it.journalId in journalIds && groupsById[it.contractorId]?.journalId == it.journalId })
    require(recordValues.all { value ->
        val record = recordsById[value.recordId]
        record != null && fieldsById[value.fieldId]?.journalId == record.journalId
    })
    require(fieldPresets.all { it.fieldId in fieldsById })
    require(fieldValueColors.all { it.fieldId in fieldsById })
}
