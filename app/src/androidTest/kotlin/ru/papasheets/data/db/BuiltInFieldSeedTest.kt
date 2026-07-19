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
 *
 * Сравнение идёт на ТЕКУЩЕЙ версии схемы, а не на v2, где миграция эти строки создаёт: с v5 обе
 * ветки пишут разные формы строки (у миграции есть колонка `key`, у сида её уже нет), и сойтись
 * они обязаны на выходе, пройдя цепочку целиком. Так проверяется то, что важно на самом деле, —
 * что у прораба, обновившегося с v1, и у прораба с чистой установки поля в итоге одинаковые.
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
        val fromMigration = migratedFieldDefs()
        val fromSeed = freshlyCreatedFieldDefs()

        assertEquals(fromMigration, fromSeed)
        assertEquals(
            listOf(BuiltInFields.LOCATION_ID, BuiltInFields.WORK_ID),
            fromSeed.map { it.first },
        )
    }

    /**
     * Путь обновившегося устройства: с самой первой версии до текущей, всеми миграциями подряд.
     *
     * Возвращённую БД закрывать нельзя: ею владеет [MigrationTestHelper] и закрывает сам при
     * разборе правила. Закрыть её здесь значит освободить нативный хендл дважды — процесс падает
     * целиком, без java-стектрейса, и роняет весь прогон инструментации, а не один тест.
     */
    private fun migratedFieldDefs(): List<Pair<String, List<String>>> {
        helper.createDatabase(MIGRATED_DB, 1).close()
        return helper.runMigrationsAndValidate(MIGRATED_DB, APP_DATABASE_VERSION, true, *Migrations.ALL)
            .readFieldDefs()
    }

    /**
     * БД, созданная с нуля: путь чистой установки, вместе с колбэком `DefaultSeed`.
     *
     * Закрывается сам Room, а не выданное им соединение: соединением владеет он, и закрытие в обход
     * оставило бы Room с уже недействительным хендлом.
     */
    private fun freshlyCreatedFieldDefs(): List<Pair<String, List<String>>> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(FRESH_DB)
        val room = Room.databaseBuilder(context, AppDatabase::class.java, FRESH_DB)
            .addCallback(DefaultSeed.callback())
            .addMigrations(*Migrations.ALL)
            .build()
        return try {
            room.openHelper.writableDatabase.readFieldDefs()
        } finally {
            room.close()
        }
    }

    /** Все колонки, кроме `createdAt`: отметка времени у веток заведомо разная и ни на что не влияет. */
    private fun SupportSQLiteDatabase.readFieldDefs(): List<Pair<String, List<String>>> =
        query(
            """
            SELECT id, title, label, orderIndex, isArchived, isBuiltIn, isRequired,
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
