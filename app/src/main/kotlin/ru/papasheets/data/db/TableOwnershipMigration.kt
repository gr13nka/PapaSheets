package ru.papasheets.data.db

import android.content.ContentValues
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ru.papasheets.exportkit.backup.LegacyTableStructure

/** Copies shared definitions before replacing them; record and photo identities never change. */
internal object TableOwnershipMigration : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA defer_foreign_keys = TRUE")
        val groups = db.rows("contractors")
        val fields = db.rows("field_defs")
        val presets = db.rows("field_presets")
        val colors = db.rows("field_value_colors")
        var journals = db.rows("journals")
        if (journals.isEmpty() && (groups.isNotEmpty() || fields.isNotEmpty())) {
            val definitions = groups + fields
            val recovery = LegacyTableStructure.recoveryJournal(definitions.map { it.getAsString("id") },
                definitions.maxOfOrNull { it.getAsLong("createdAt") } ?: 0)
            db.execSQL("INSERT INTO journals (id, year, month, title, createdAt) VALUES (?, ?, ?, ?, ?)",
                arrayOf(recovery.id, recovery.year, recovery.month, recovery.title, recovery.createdAt))
            journals = db.rows("journals")
        }
        // Existing columns copied from exported schema 7; only journal ownership is added.
        db.execSQL("CREATE TABLE `contractors_owned` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `shortName` TEXT NOT NULL, `colorIndex` INTEGER NOT NULL, `orderIndex` INTEGER NOT NULL, `isArchived` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `journalId` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`journalId`) REFERENCES `journals`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE TABLE `field_defs_owned` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `label` TEXT NOT NULL, `orderIndex` INTEGER NOT NULL, `isArchived` INTEGER NOT NULL, `isBuiltIn` INTEGER NOT NULL, `isRequired` INTEGER NOT NULL, `suggestFromHistory` INTEGER NOT NULL, `columnWidthDp` INTEGER NOT NULL, `maxLines` INTEGER NOT NULL, `showAtCompactLod` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `journalId` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`journalId`) REFERENCES `journals`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("DELETE FROM field_presets")
        db.execSQL("DELETE FROM field_value_colors")
        journals.forEach { journal ->
            val journalId = journal.getAsString("id")
            groups.forEach { old ->
                val groupId = LegacyTableStructure.id("group", journalId, old.getAsString("id"))
                db.insert("contractors_owned", SQLiteDatabase.CONFLICT_ABORT, ContentValues(old).apply {
                    put("id", groupId); put("journalId", journalId)
                })
                db.execSQL("UPDATE records SET contractorId = ? WHERE journalId = ? AND contractorId = ?",
                    arrayOf(groupId, journalId, old.getAsString("id")))
            }
            fields.forEach { old ->
                val fieldId = LegacyTableStructure.id("field", journalId, old.getAsString("id"))
                db.insert("field_defs_owned", SQLiteDatabase.CONFLICT_ABORT, ContentValues(old).apply {
                    put("id", fieldId); put("journalId", journalId); put("isBuiltIn", 0)
                })
                db.execSQL("UPDATE record_values SET fieldId = ? WHERE fieldId = ? AND recordId IN (SELECT id FROM records WHERE journalId = ?)",
                    arrayOf(fieldId, old.getAsString("id"), journalId))
            }
            presets.forEach { old ->
                db.insert("field_presets", SQLiteDatabase.CONFLICT_ABORT, ContentValues(old).apply {
                    put("id", LegacyTableStructure.id("preset", journalId, old.getAsString("id")))
                    put("fieldId", LegacyTableStructure.id("field", journalId, old.getAsString("fieldId")))
                })
            }
            colors.forEach { old ->
                db.insert("field_value_colors", SQLiteDatabase.CONFLICT_ABORT, ContentValues(old).apply {
                    put("fieldId", LegacyTableStructure.id("field", journalId, old.getAsString("fieldId")))
                })
            }
        }
        // As in earlier migrations, Room enables foreign keys in onOpen, after migration.
        db.execSQL("DROP TABLE contractors")
        db.execSQL("ALTER TABLE contractors_owned RENAME TO contractors")
        db.execSQL("DROP TABLE field_defs")
        db.execSQL("ALTER TABLE field_defs_owned RENAME TO field_defs")
        db.execSQL("CREATE INDEX index_contractors_journalId ON contractors(journalId)")
        db.execSQL("CREATE INDEX index_field_defs_journalId ON field_defs(journalId)")
        db.execSQL("DROP INDEX index_journals_year_month")
        db.execSQL("CREATE INDEX index_journals_year_month ON journals(year, month)")
    }

    private fun SupportSQLiteDatabase.rows(table: String): List<ContentValues> = query("SELECT * FROM $table").use { cursor ->
        buildList { while (cursor.moveToNext()) add(ContentValues().also { DatabaseUtils.cursorRowToContentValues(cursor, it) }) }
    }
}
