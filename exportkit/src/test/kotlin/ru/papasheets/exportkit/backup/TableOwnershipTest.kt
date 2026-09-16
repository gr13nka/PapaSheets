package ru.papasheets.exportkit.backup

import org.junit.Assert.*
import org.junit.Test

class TableOwnershipTest {
    private fun shared() = BackupData(
        journals = listOf(BackupJournal("a", 2026, 8, "A", 1), BackupJournal("b", 2026, 9, "B", 2)),
        contractors = listOf(BackupContractor("group", "Team", "T", 2, 0, true, 1)),
        fieldDefs = listOf(BackupFieldDef("field", "Work", "Work", 0, true, false, false, true, 77, 3, true, 1)),
        fieldPresets = listOf(BackupFieldPreset("preset", "field", "Plaster", 0)),
        fieldValueColors = listOf(BackupFieldValueColor("field", "Plaster", 3)),
        records = listOf(
            BackupRecord("r1", "a", 100, "group", photoId = "p1", photoId2 = "p2", createdAt = 1, updatedAt = 2),
            BackupRecord("r2", "b", 101, "group", photoId = null, createdAt = 1, updatedAt = 2),
        ),
        recordValues = listOf(BackupRecordValue("r1", "field", "Plaster"), BackupRecordValue("r2", "field", "Brick")),
        photos = emptyList(),
    )

    @Test fun oldSharedStructureBecomesIndependentWithoutChangingContent() {
        val old = shared()
        val current = BackupUpgrade.toCurrent(6, old)
        current.validateOwnership()
        assertEquals(2, current.fieldDefs.size)
        assertEquals(2, current.fieldDefs.map { it.id }.toSet().size)
        assertEquals(setOf("a", "b"), current.fieldDefs.map { it.journalId }.toSet())
        assertTrue(current.fieldDefs.all { it.columnWidthDp == 77 && it.isArchived })
        assertEquals(2, current.fieldPresets.map { it.id }.toSet().size)
        assertEquals(2, current.fieldValueColors.size)
        assertEquals(old.recordValues.map { it.value }, current.recordValues.map { it.value })
        assertEquals("p1", current.records.first().photoId)
        assertEquals("p2", current.records.first().photoId2)
        assertEquals(current, BackupUpgrade.toCurrent(6, old))
        assertEquals(current, BackupUpgrade.toCurrent(7, current))
    }

    @Test fun currentBackupCannotPointIntoAnotherTablesFields() {
        val valid = BackupUpgrade.toCurrent(6, shared())
        val otherField = valid.fieldDefs.single { it.journalId == "b" }.id
        val invalid = valid.copy(recordValues = listOf(BackupRecordValue("r1", otherField, "Wrong")))
        assertTrue(runCatching { invalid.validateOwnership() }.isFailure)
    }

    @Test fun structureWithoutAJournalRemainsReusable() {
        val source = shared().copy(journals = emptyList(), records = emptyList(), recordValues = emptyList())
        val current = BackupUpgrade.toCurrent(6, source)
        current.validateOwnership()
        assertEquals("Previous setup", current.journals.single().title)
        assertEquals(current.journals.single().id, current.fieldDefs.single().journalId)
        assertEquals(1, current.fieldPresets.size)
    }
}
