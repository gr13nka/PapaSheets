package ru.papasheets.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.papasheets.exportkit.backup.BuiltInFields

/**
 * Миграция v1 → v2 на настоящем SQLite.
 *
 * Единственный необратимый шаг в проекте: на телефоне лежит рабочий журнал прораба без второй
 * копии, и ошибка здесь означает молча испорченные данные. На JVM это не проверяется — нужен
 * реальный SQLite и сверка результата с экспортированной схемой.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    /** Переносится ровно непустое содержимое старых колонок, и ничего сверх того. */
    @Test
    fun migration1To2_movesOnlyNonBlankValues() {
        helper.createDatabase(TEST_DB, 1).use { it.seedV1Fixtures() }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, Migrations.MIGRATION_1_2)

        assertEquals(
            listOf(BuiltInFields.LOCATION_ID, BuiltInFields.WORK_ID),
            db.queryStrings("SELECT id FROM field_defs ORDER BY orderIndex"),
        )
        assertEquals(
            setOf(
                Triple("r-full", BuiltInFields.LOCATION_ID, "К1"),
                Triple("r-full", BuiltInFields.WORK_ID, "Штукатурка"),
                Triple("r-no-location", BuiltInFields.WORK_ID, "Плитка"),
                // Локация из одних пробелов приравнивается к пустой (TRIM в миграции).
                Triple("r-blank-location", BuiltInFields.WORK_ID, "Плинтус"),
                // Пробелы по краям срезаются: иначе « К2 » и «К2» разъехались бы в подсказках как разные.
                Triple("r-padded", BuiltInFields.LOCATION_ID, "К2"),
                Triple("r-padded", BuiltInFields.WORK_ID, "Стяжка"),
            ),
            db.queryValueTriples(),
        )
        // Старые колонки не тронуты: если перенос окажется неполным, чинить будем по ним.
        assertEquals(5L, db.queryLong("SELECT COUNT(*) FROM records"))
        assertEquals("  К2  ", db.queryStrings("SELECT locationCode FROM records WHERE id = 'r-padded'").single())
        assertEquals("К1", db.queryStrings("SELECT locationCode FROM records WHERE id = 'r-full'").single())
        assertTrue(db.queryStrings("PRAGMA foreign_key_check").isEmpty())
    }

    /**
     * Открытие мигрированной БД настоящим `Room.databaseBuilder` — проверка identityHash.
     *
     * Ради неё androidTest и заведён: если DDL в миграции хоть на символ разойдётся с тем, что
     * Room ожидает по схеме, приложение упадёт при первом же запуске после обновления. Здесь это
     * видно на CI, а не на телефоне у прораба.
     */
    @Test
    fun migratedDatabase_opensWithRoom() {
        helper.createDatabase(TEST_DB, 1).use { it.seedV1Fixtures() }

        val room = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            TEST_DB,
        ).addMigrations(Migrations.MIGRATION_1_2).build()

        try {
            // Открытие БД ленивое: миграция и сверка хеша происходят на первом обращении.
            val opened = room.openHelper.writableDatabase
            assertEquals(2, opened.version)
            assertEquals(2L, opened.queryLong("SELECT COUNT(*) FROM field_defs"))
        } finally {
            room.close()
        }
    }

    private fun SupportSQLiteDatabase.seedV1Fixtures() {
        execSQL("INSERT INTO journals VALUES ('j1', 2026, 7, 'Июль', 0)")
        execSQL("INSERT INTO contractors VALUES ('c1', 'Г.П.', 'ГП', 0, 0, 0, 0)")
        insertV1Record("r-full", location = "К1", work = "Штукатурка")
        insertV1Record("r-no-location", location = "", work = "Плитка")
        insertV1Record("r-blank-location", location = "   ", work = "Плинтус")
        insertV1Record("r-padded", location = "  К2  ", work = " Стяжка ")
        insertV1Record("r-empty", location = "", work = "")
    }

    private fun SupportSQLiteDatabase.insertV1Record(id: String, location: String, work: String) {
        execSQL(
            "INSERT INTO records VALUES (?, 'j1', 20000, 'c1', ?, ?, NULL, 0, 0)",
            arrayOf(id, location, work),
        )
    }

    private fun SupportSQLiteDatabase.queryStrings(sql: String): List<String> =
        query(sql).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun SupportSQLiteDatabase.queryLong(sql: String): Long =
        query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    private fun SupportSQLiteDatabase.queryValueTriples(): Set<Triple<String, String, String>> =
        query("SELECT recordId, fieldId, value FROM record_values").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    add(Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
                }
            }
        }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
