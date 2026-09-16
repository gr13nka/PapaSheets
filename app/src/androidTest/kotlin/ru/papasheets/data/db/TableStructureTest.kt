package ru.papasheets.data.db

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.papasheets.data.db.entity.*
import ru.papasheets.data.repo.*

@RunWith(AndroidJUnit4::class)
class TableStructureTest {
    private val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
    private val structures = TableStructureRepository(db)
    private val transaction = object : TransactionRunner {
        override suspend fun <T> run(block: suspend () -> T): T = db.withTransaction(block)
    }
    private val records = RecordRepository(db.recordDao(), db.recordValueDao(), transaction)

    @After fun close() = db.close()

    @Test fun freshDatabaseHasNoGlobalDefinitions() = runBlocking {
        assertTrue(db.fieldDefDao().getAll().isEmpty())
        assertTrue(db.journalDao().getAll().isEmpty())
        val id = structures.create("Site A", 2026, 9, null, "General", "Description")
        assertEquals(1, db.contractorDao().getForJournal(id).size)
        assertFalse(db.fieldDefDao().getForJournal(id).single().isRequired)
    }

    @Test fun copiedStructureIsIndependentAndContainsNoRecordsOrPhotos() = runBlocking {
        val a = structures.create("Site A", 2026, 9, null, "General", "Description")
        val group = db.contractorDao().getForJournal(a).single()
        val field = db.fieldDefDao().getForJournal(a).single()
        db.fieldPresetDao().insert(FieldPresetEntity("preset", field.id, "Plaster", 0))
        db.fieldValueColorDao().upsert(FieldValueColorEntity(field.id, "Plaster", 3))
        db.photoDao().insert(ru.papasheets.data.db.entity.PhotoEntity("photo1", 100, 100, 10, null, 0))
        db.photoDao().insert(ru.papasheets.data.db.entity.PhotoEntity("photo2", 100, 100, 10, null, 0))
        records.createRecord(a, 20000, group.id, mapOf(field.id to "Plaster"), "photo1", "photo2")
        val b = structures.create("Site A", 2026, 9, a, "unused", "unused")
        val copiedGroup = db.contractorDao().getForJournal(b).single()
        val copiedField = db.fieldDefDao().getForJournal(b).single()
        assertNotEquals(group.id, copiedGroup.id)
        assertNotEquals(field.id, copiedField.id)
        assertEquals(listOf("Plaster"), db.fieldPresetDao().presetsFor(copiedField.id).map { it.code })
        assertEquals(3, db.fieldValueColorDao().getAll().single { it.fieldId == copiedField.id }.colorIndex)
        assertTrue(db.recordDao().getAll().none { it.journalId == b })
        FieldRepository(db.fieldDefDao()).edit(copiedField.id) { it.copy(label = "Different") }
        assertEquals("Description", db.fieldDefDao().getForJournal(a).single().label)
        db.journalDao().deleteTable(b)
        assertEquals(1, db.journalDao().getAll().size)
        assertEquals(listOf("photo1", "photo2"), db.recordDao().getAll().single().photoIds)
        assertEquals(1, db.fieldDefDao().getAll().size)
    }

    @Test fun foreignTableReferencesAndMissingCopySourceRollBack() = runBlocking {
        val a = structures.create("A", 2026, 9, null, "General", "Description")
        val b = structures.create("B", 2026, 9, null, "General", "Description")
        val group = db.contractorDao().getForJournal(a).single()
        val field = db.fieldDefDao().getForJournal(b).single()
        assertTrue(runCatching { records.createRecord(a, 20000, group.id, mapOf(field.id to "Wrong"), null, null) }.isFailure)
        assertTrue(db.recordDao().getAll().isEmpty())
        assertTrue(runCatching { structures.create("Broken", 2026, 9, "missing", "General", "Description") }.isFailure)
        assertEquals(2, db.journalDao().getAll().size)
    }
}
