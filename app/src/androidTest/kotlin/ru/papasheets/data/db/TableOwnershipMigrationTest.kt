package ru.papasheets.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.papasheets.exportkit.backup.LegacyTableStructure

@RunWith(AndroidJUnit4::class)
class TableOwnershipMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun sharedDefinitionsSplitWithoutLosingArchivedValuesOrSecondPhotos() {
        helper.createDatabase("ownership-migration", 7).use { db ->
            db.execSQL("INSERT INTO journals VALUES ('j1',2026,8,'A',1), ('j2',2026,9,'B',2)")
            db.execSQL("INSERT INTO contractors VALUES ('c','Team','T',3,0,1,1)")
            db.execSQL("INSERT INTO field_defs VALUES ('f','Description','Description',0,1,0,0,1,168,0,1,1)")
            db.execSQL("INSERT INTO field_presets VALUES ('preset','f','Plaster',0)")
            db.execSQL("INSERT INTO field_value_colors VALUES ('f','Plaster',3)")
            db.execSQL("INSERT INTO photos VALUES ('p1',100,100,10,NULL,1), ('p2',100,100,10,NULL,1)")
            db.execSQL("INSERT INTO records VALUES ('r1','j1',20000,'c','p1','p2',1,1), ('r2','j2',20001,'c',NULL,NULL,1,1)")
            db.execSQL("INSERT INTO record_values VALUES ('r1','f','Plaster'), ('r2','f','Brick')")
        }
        val db = helper.runMigrationsAndValidate("ownership-migration", 8, true, Migrations.MIGRATION_7_8)
        db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        db.query("SELECT journalId, fieldId, value FROM records JOIN record_values ON records.id=recordId ORDER BY journalId").use { c ->
            assertTrue(c.moveToNext())
            assertEquals(LegacyTableStructure.id("field", "j1", "f"), c.getString(1))
            assertEquals("Plaster", c.getString(2))
            assertTrue(c.moveToNext())
            assertEquals(LegacyTableStructure.id("field", "j2", "f"), c.getString(1))
            assertEquals("Brick", c.getString(2))
        }
        for (table in listOf("contractors", "field_defs", "field_presets", "field_value_colors")) {
            db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); assertEquals(table, 2, it.getInt(0)) }
        }
        db.query("SELECT photoId,photoId2 FROM records WHERE id='r1'").use { it.moveToFirst(); assertEquals("p1", it.getString(0)); assertEquals("p2", it.getString(1)) }
    }
}
