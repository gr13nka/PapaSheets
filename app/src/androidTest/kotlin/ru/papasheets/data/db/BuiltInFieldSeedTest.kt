package ru.papasheets.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.papasheets.data.DefaultSeed
import ru.papasheets.exportkit.backup.BuiltInFields

/**
 * Встроенные поля появляются двумя независимыми путями — сид при создании БД и миграция с v1 —
 * и обязаны получиться одинаковыми.
 *
 * Ветки не пересекаются (`onCreate` не вызывается при апгрейде, миграция — при создании), поэтому
 * расхождение между ними ничем себя не проявило бы до первого бэкапа с одного устройства на другое:
 * там встроенное поле узнаётся по id, и разошедшиеся строки дали бы две колонки «Локация» рядом.
 * Тест сравнивает результат обеих веток целиком, а не только id.
 */
@RunWith(AndroidJUnit4::class)
class BuiltInFieldSeedTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun freshInstallAndMigration_produceIdenticalFieldDefs() {
        val fromMigration = migratedDatabase().use { it.readFieldDefs() }
        val fromSeed = freshlyCreatedDatabase().use { it.readFieldDefs() }

        assertEquals(fromMigration, fromSeed)
        assertEquals(
            listOf(BuiltInFields.LOCATION_ID, BuiltInFields.WORK_ID),
            fromSeed.map { it.first },
        )
    }

    private fun migratedDatabase(): SupportSQLiteDatabase {
        helper.createDatabase(MIGRATED_DB, 1).close()
        return helper.runMigrationsAndValidate(MIGRATED_DB, 2, true, Migrations.MIGRATION_1_2)
    }

    /** БД, созданная с нуля: путь чистой установки, вместе с колбэком `DefaultSeed`. */
    private fun freshlyCreatedDatabase(): SupportSQLiteDatabase {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(FRESH_DB)
        val room = Room.databaseBuilder(context, AppDatabase::class.java, FRESH_DB)
            .addCallback(DefaultSeed.callback())
            .addMigrations(Migrations.MIGRATION_1_2)
            .build()
        return room.openHelper.writableDatabase
    }

    /** Все колонки, кроме `createdAt`: отметка времени у веток заведомо разная и ни на что не влияет. */
    private fun SupportSQLiteDatabase.readFieldDefs(): List<Pair<String, List<String>>> =
        query(
            """
            SELECT id, key, title, label, orderIndex, isArchived, isBuiltIn, isRequired,
                   suggestFromHistory, columnWidthDp, maxLines, showAtCompactLod
            FROM field_defs ORDER BY orderIndex
            """,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val columns = (0 until cursor.columnCount).map { cursor.getString(it) }
                    add(cursor.getString(0) to columns)
                }
            }
        }

    private companion object {
        const val MIGRATED_DB = "seed-parity-migrated.db"
        const val FRESH_DB = "seed-parity-fresh.db"
    }
}
