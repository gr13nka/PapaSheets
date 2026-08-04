package ru.papasheets.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.papasheets.exportkit.backup.BuiltInFields

/**
 * Миграции схемы на настоящем SQLite.
 *
 * Единственный необратимый участок проекта: на телефоне лежит рабочий журнал прораба без второй
 * копии, и ошибка здесь означает молча испорченные данные. На JVM это не проверяется — нужен
 * реальный SQLite и сверка результата с экспортированной схемой.
 *
 * Проверяются и отдельные шаги, и цепочка целиком: на устройстве может стоять сборка любой
 * давности, поэтому путь «с версии N сразу до текущей» — такой же рабочий сценарий, как последний
 * шаг, и ломается он ровно так же незаметно.
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
        // На этом шаге старые колонки ещё на месте: страховка на случай неполного переноса.
        assertEquals(5L, db.queryLong("SELECT COUNT(*) FROM records"))
        assertEquals("  К2  ", db.queryStrings("SELECT locationCode FROM records WHERE id = 'r-padded'").single())
        assertEquals("К1", db.queryStrings("SELECT locationCode FROM records WHERE id = 'r-full'").single())
        assertTrue(db.queryStrings("PRAGMA foreign_key_check").isEmpty())
    }

    /**
     * Пересоздание `records` не теряет записи и не сиротит их значения.
     *
     * Единственная миграция проекта, которая сносит таблицу с данными: `DROP`/`RENAME` рвут ссылку
     * `record_values.recordId` на время подмены, поэтому проверяется не только целость строк, но и
     * то, что отложенная проверка внешних ключей после коммита чиста.
     */
    @Test
    fun migration2To3_dropsDeadColumnsKeepingRecordsAndValues() {
        helper.createDatabase(TEST_DB, 2).use { it.seedV2Fixtures() }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, Migrations.MIGRATION_2_3)

        assertEquals(
            listOf("id", "journalId", "dateEpochDay", "contractorId", "photoId", "createdAt", "updatedAt"),
            db.columnsOf("records"),
        )
        assertEquals(
            setOf("r-1" to "c1", "r-2" to "c1"),
            db.queryPairs("SELECT id, contractorId FROM records"),
        )
        assertEquals(
            setOf(
                Triple("r-1", BuiltInFields.LOCATION_ID, "К1"),
                Triple("r-1", BuiltInFields.WORK_ID, "Штукатурка"),
                Triple("r-2", BuiltInFields.WORK_ID, "Плитка"),
            ),
            db.queryValueTriples(),
        )
        assertNoOrphanValues(db)
        assertTrue(db.queryStrings("PRAGMA foreign_key_check").isEmpty())
    }

    /**
     * Пресеты переезжают с локации на поле, не потеряв ни строки и не потеряв порядок.
     *
     * `location_presets` не знала владельца — у неё был ровно один, встроенная «Локация», — поэтому
     * миграция обязана проставить `fieldId` всем строкам разом. Внешний ключ на `field_defs` здесь
     * же и проверяется: под несуществующим полем строки остаться не должно.
     */
    @Test
    fun migration3To4_movesPresetsUnderTheBuiltInLocationField() {
        helper.createDatabase(TEST_DB, 3).use { it.seedV3Fixtures() }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, Migrations.MIGRATION_3_4)

        assertEquals(
            listOf(
                Triple("p-1", BuiltInFields.LOCATION_ID, "К1"),
                Triple("p-2", BuiltInFields.LOCATION_ID, "К2"),
            ),
            db.queryPresets(),
        )
        assertTrue(db.queryStrings("PRAGMA foreign_key_check").isEmpty())
    }

    /**
     * Удаление поля уносит его пресеты (`ON DELETE CASCADE`), но не значения записей (`RESTRICT`).
     *
     * Разное поведение двух ссылок на `field_defs` — сознательное решение, а не случайность: пресеты
     * это настройка автодополнения, а `record_values` — содержимое журнала прораба. Проверяется
     * именно на мигрировавшей БД: `ON DELETE` живёт в DDL, и потерять его при переносе таблицы
     * было бы незаметно вплоть до первого удаления поля.
     */
    @Test
    fun migratedSchema_dropsPresetsWithTheirFieldButGuardsRecordValues() {
        helper.createDatabase(TEST_DB, 3).use { it.seedV3Fixtures() }
        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, Migrations.MIGRATION_3_4)
        db.execSQL("PRAGMA foreign_keys = ON")

        // «Локация» пуста в записях — удаляется, унося свои пресеты.
        db.execSQL("DELETE FROM field_defs WHERE id = ?", arrayOf(BuiltInFields.LOCATION_ID))
        assertTrue(db.queryPresets().isEmpty())

        // «Вид работ» заполнен — SQLite не даст его удалить, и это та самая защита данных прораба,
        // ради которой FieldRepository отказывает раньше, внятным ответом вместо исключения.
        try {
            db.execSQL("DELETE FROM field_defs WHERE id = ?", arrayOf(BuiltInFields.WORK_ID))
            fail("ожидался отказ по внешнему ключу record_values → field_defs")
        } catch (e: SQLiteConstraintException) {
            assertTrue(e.message!!.isNotBlank())
        }
        assertEquals(1L, db.queryLong("SELECT COUNT(*) FROM field_defs"))
    }

    /**
     * `field_defs` пересоздаётся без колонки `key`, не потеряв ни полей, ни всего, что на них висит.
     *
     * Вторая после [Migrations.MIGRATION_2_3] миграция, сносящая таблицу с данными, и здесь ставка
     * выше: на `field_defs` ссылаются сразу двое — `field_presets` с `ON DELETE CASCADE` и
     * `record_values` с `ON DELETE RESTRICT`. Будь внешние ключи во время миграции включены, неявный
     * DELETE от `DROP TABLE` унёс бы каскадом все пресеты, а на значениях упёрся бы в RESTRICT.
     * Поэтому проверяется не только то, что колонка ушла, но и что и пресеты, и значения на месте.
     */
    @Test
    fun migration4To5_dropsTheFieldKeyKeepingFieldsPresetsAndValues() {
        helper.createDatabase(TEST_DB, 4).use { it.seedV4Fixtures() }

        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, Migrations.MIGRATION_4_5)

        assertEquals(
            listOf(
                "id", "title", "label", "orderIndex", "isArchived", "isBuiltIn", "isRequired",
                "suggestFromHistory", "columnWidthDp", "maxLines", "showAtCompactLod", "createdAt",
            ),
            db.columnsOf("field_defs"),
        )
        // Поля целы и в прежнем порядке — вместе со своими настройками, а не сброшенные к заводским.
        assertEquals(
            listOf(BuiltInFields.LOCATION_ID, BuiltInFields.WORK_ID, "f-custom"),
            db.queryStrings("SELECT id FROM field_defs ORDER BY orderIndex"),
        )
        assertEquals(
            listOf("Мои локации"),
            db.queryStrings("SELECT title FROM field_defs WHERE id = '${BuiltInFields.LOCATION_ID}'"),
        )
        // Пресеты не унесло каскадом от DROP TABLE.
        assertEquals(listOf(Triple("p-1", BuiltInFields.LOCATION_ID, "К1")), db.queryPresets())
        // Значения записей — тоже; ради них вся осторожность и нужна.
        assertEquals(
            setOf(
                Triple("r-1", BuiltInFields.WORK_ID, "Штукатурка"),
                Triple("r-1", "f-custom", "12 м²"),
            ),
            db.queryValueTriples(),
        )
        assertTrue(db.queryStrings("PRAGMA foreign_key_check").isEmpty())
    }

    /**
     * После пересоздания `field_defs` обе ссылки на неё ведут себя по-прежнему.
     *
     * `ON DELETE` живёт в DDL таблиц-ссылающихся, но пересоздание цели — ровно тот момент, когда
     * ссылка может тихо перестать существовать: имя таблицы на время пропадает из схемы. Потеря
     * RESTRICT обнаружилась бы только первым удалением заполненного поля — то есть стёртыми данными.
     */
    @Test
    fun migration4To5_keepsCascadeAndRestrictOnTheRecreatedTable() {
        helper.createDatabase(TEST_DB, 4).use { it.seedV4Fixtures() }
        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, Migrations.MIGRATION_4_5)
        db.execSQL("PRAGMA foreign_keys = ON")

        // «Локация» в записях пуста — удаляется, унося свои пресеты.
        db.execSQL("DELETE FROM field_defs WHERE id = ?", arrayOf(BuiltInFields.LOCATION_ID))
        assertTrue(db.queryPresets().isEmpty())

        try {
            db.execSQL("DELETE FROM field_defs WHERE id = ?", arrayOf(BuiltInFields.WORK_ID))
            fail("ожидался отказ по внешнему ключу record_values → field_defs")
        } catch (e: SQLiteConstraintException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    /**
     * У записи появляется второй слот фото — аддитивным `ADD COLUMN`, не трогая ни строки.
     *
     * Единственная миграция, которая касается `records` без пересоздания: у существующих записей
     * второго фото не было, поэтому колонка добавляется со значением по умолчанию NULL. Проверяется,
     * что записи целы, новый слот у них пуст, а UNIQUE-индекс на нём работает — иначе два фото
     * встали бы в один слот незаметно.
     */
    @Test
    fun migration5To6_addsSecondPhotoSlotKeepingRecords() {
        helper.createDatabase(TEST_DB, 5).use { it.seedV5Fixtures() }

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, Migrations.MIGRATION_5_6)

        assertTrue("photoId2" in db.columnsOf("records"))
        // Записи на месте: у первой фото в слоте 1, второй слот пуст; у второй — оба пусты.
        assertEquals(
            setOf("r-1" to "ph1", "r-2" to null),
            db.query("SELECT id, photoId FROM records").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
            },
        )
        assertEquals(2L, db.queryLong("SELECT COUNT(*) FROM records WHERE photoId2 IS NULL"))

        // UNIQUE на photoId2: одно фото не может оказаться во вторых слотах двух записей.
        db.execSQL("UPDATE records SET photoId2 = 'ph2' WHERE id = 'r-1'")
        try {
            db.execSQL("UPDATE records SET photoId2 = 'ph2' WHERE id = 'r-2'")
            fail("ожидался отказ по уникальному индексу index_records_photoId2")
        } catch (e: SQLiteConstraintException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    /**
     * v6 → v7: таблица цветов значений заводится рядом, ничего не трогая.
     *
     * Проверяется не только появление таблицы, но и два свойства, ради которых она так устроена:
     * ключ (поле, значение) перекрашивает значение вместо того, чтобы завести вторую строку, и
     * цвета умирают вместе со своим полем — иначе удаление поля оставляло бы мусор навсегда.
     */
    @Test
    fun migration6To7_addsValueColorsCascadingWithFields() {
        helper.createDatabase(TEST_DB, 6).use { it.seedV6Fixtures() }

        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, Migrations.MIGRATION_6_7)

        // Таблица пуста: до v7 цвет значению назначить было негде, переливать нечего.
        assertEquals(0L, db.queryLong("SELECT COUNT(*) FROM field_value_colors"))
        // И существующие данные шаг не двигал.
        assertEquals(2L, db.queryLong("SELECT COUNT(*) FROM records"))

        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL(
            "INSERT INTO field_value_colors VALUES (?, 'Штукатурка', 3)",
            arrayOf(BuiltInFields.WORK_ID),
        )

        // (fieldId, value) — первичный ключ: повторная покраска того же значения его меняет.
        db.execSQL(
            "INSERT OR REPLACE INTO field_value_colors VALUES (?, 'Штукатурка', 7)",
            arrayOf(BuiltInFields.WORK_ID),
        )
        assertEquals(1L, db.queryLong("SELECT COUNT(*) FROM field_value_colors"))
        assertEquals(7L, db.queryLong("SELECT colorIndex FROM field_value_colors"))

        // Цвета — часть определения поля и уходят вместе с ним (ON DELETE CASCADE).
        db.execSQL("DELETE FROM field_defs WHERE id = ?", arrayOf(BuiltInFields.WORK_ID))
        assertEquals(0L, db.queryLong("SELECT COUNT(*) FROM field_value_colors"))
    }

    /**
     * Путь с самой первой версии до текущей одним прогоном.
     *
     * Отдельные тесты шагов не покрывают именно этот сценарий: у прораба может стоять сборка,
     * отставшая на несколько версий, и сломаться способна как раз стыковка шагов, а не шаг сам по
     * себе. Версия и список миграций берутся из продакшн-кода, поэтому очередной bump ничего здесь
     * править не требует — но и пропустить миграцию в [Migrations.ALL] не даёт.
     */
    @Test
    fun migrationChain_fromFirstVersionToCurrent_keepsData() {
        helper.createDatabase(TEST_DB, 1).use { it.seedV1Fixtures() }

        val db = helper.runMigrationsAndValidate(TEST_DB, CURRENT_VERSION, true, *Migrations.ALL)

        assertEquals(5L, db.queryLong("SELECT COUNT(*) FROM records"))
        assertEquals(
            setOf(
                Triple("r-full", BuiltInFields.LOCATION_ID, "К1"),
                Triple("r-full", BuiltInFields.WORK_ID, "Штукатурка"),
                Triple("r-no-location", BuiltInFields.WORK_ID, "Плитка"),
                Triple("r-blank-location", BuiltInFields.WORK_ID, "Плинтус"),
                Triple("r-padded", BuiltInFields.LOCATION_ID, "К2"),
                Triple("r-padded", BuiltInFields.WORK_ID, "Стяжка"),
            ),
            db.queryValueTriples(),
        )
        // Пресет, заведённый ещё в v1, доезжает до текущей версии и обретает поле-владельца.
        assertEquals(listOf(Triple("p-1", BuiltInFields.LOCATION_ID, "К1")), db.queryPresets())
        // И последний шаг тоже отработал, а не потерялся за более ранними: машинного ключа у поля
        // больше нет, а сами поля, заведённые ещё миграцией 1 → 2, на месте.
        assertTrue("key" !in db.columnsOf("field_defs"))
        assertEquals(
            listOf(BuiltInFields.LOCATION_ID, BuiltInFields.WORK_ID),
            db.queryStrings("SELECT id FROM field_defs ORDER BY orderIndex"),
        )
        assertNoOrphanValues(db)
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
        ).addMigrations(*Migrations.ALL).build()

        try {
            // Открытие БД ленивое: миграция и сверка хеша происходят на первом обращении.
            val opened = room.openHelper.writableDatabase
            assertEquals(CURRENT_VERSION, opened.version)
            assertEquals(2L, opened.queryLong("SELECT COUNT(*) FROM field_defs"))

            // Значения обязаны пережить путь целиком ИМЕННО на продакшн-пути открытия.
            // MIGRATION_2_3 сносит `records` через DROP TABLE, а `record_values.recordId` объявлен
            // с ON DELETE CASCADE: при включённых внешних ключах DROP выполняет неявный DELETE и
            // каскад унёс бы всё содержимое журнала. `defer_foreign_keys` откладывает проверку
            // НАРУШЕНИЙ, но каскадные действия не отменяет — так что вопрос решает только то, в
            // каком состоянии внешние ключи на самом деле находятся во время onUpgrade.
            // MigrationTestHelper это не докажет: у него своя настройка соединения.
            assertEquals(
                setOf(
                    Triple("r-full", BuiltInFields.LOCATION_ID, "К1"),
                    Triple("r-full", BuiltInFields.WORK_ID, "Штукатурка"),
                    Triple("r-no-location", BuiltInFields.WORK_ID, "Плитка"),
                    Triple("r-blank-location", BuiltInFields.WORK_ID, "Плинтус"),
                    Triple("r-padded", BuiltInFields.LOCATION_ID, "К2"),
                    Triple("r-padded", BuiltInFields.WORK_ID, "Стяжка"),
                ),
                opened.queryValueTriples(),
            )
            assertEquals(5L, opened.queryLong("SELECT COUNT(*) FROM records"))
        } finally {
            room.close()
        }
    }

    private fun assertNoOrphanValues(db: SupportSQLiteDatabase) {
        assertEquals(
            0L,
            db.queryLong(
                "SELECT COUNT(*) FROM record_values v " +
                    "LEFT JOIN records r ON r.id = v.recordId WHERE r.id IS NULL",
            ),
        )
    }

    private fun SupportSQLiteDatabase.seedV1Fixtures() {
        execSQL("INSERT INTO journals VALUES ('j1', 2026, 7, 'Июль', 0)")
        execSQL("INSERT INTO contractors VALUES ('c1', 'Г.П.', 'ГП', 0, 0, 0, 0)")
        insertV1Record("r-full", location = "К1", work = "Штукатурка")
        insertV1Record("r-no-location", location = "", work = "Плитка")
        insertV1Record("r-blank-location", location = "   ", work = "Плинтус")
        insertV1Record("r-padded", location = "  К2  ", work = " Стяжка ")
        insertV1Record("r-empty", location = "", work = "")
        execSQL("INSERT INTO location_presets VALUES ('p-1', 'К1', 0)")
    }

    /** Состояние после v2: содержимое уже в `record_values`, старые колонки пустые, но ещё есть. */
    private fun SupportSQLiteDatabase.seedV2Fixtures() {
        execSQL("INSERT INTO journals VALUES ('j1', 2026, 7, 'Июль', 0)")
        execSQL("INSERT INTO contractors VALUES ('c1', 'Г.П.', 'ГП', 0, 0, 0, 0)")
        insertV1Record("r-1", location = "", work = "")
        insertV1Record("r-2", location = "", work = "")
        insertFieldDef(BuiltInFields.LOCATION_ID, key = "location", orderIndex = 0)
        insertFieldDef(BuiltInFields.WORK_ID, key = "work", orderIndex = 1)
        insertValue("r-1", BuiltInFields.LOCATION_ID, "К1")
        insertValue("r-1", BuiltInFields.WORK_ID, "Штукатурка")
        insertValue("r-2", BuiltInFields.WORK_ID, "Плитка")
    }

    /**
     * Состояние после v3: записи без мёртвых колонок, пресеты — ещё в `location_presets` и без
     * владельца. У «Вида работ» есть значение, у «Локации» нет: на этой разнице проверяется, что
     * CASCADE и RESTRICT после миграции ведут себя по-разному.
     */
    private fun SupportSQLiteDatabase.seedV3Fixtures() {
        execSQL("INSERT INTO journals VALUES ('j1', 2026, 7, 'Июль', 0)")
        execSQL("INSERT INTO contractors VALUES ('c1', 'Г.П.', 'ГП', 0, 0, 0, 0)")
        execSQL("INSERT INTO records VALUES ('r-1', 'j1', 20000, 'c1', NULL, 0, 0)")
        insertFieldDef(BuiltInFields.LOCATION_ID, key = "location", orderIndex = 0)
        insertFieldDef(BuiltInFields.WORK_ID, key = "work", orderIndex = 1)
        insertValue("r-1", BuiltInFields.WORK_ID, "Штукатурка")
        execSQL("INSERT INTO location_presets VALUES ('p-1', 'К1', 0)")
        execSQL("INSERT INTO location_presets VALUES ('p-2', 'К2', 1)")
    }

    /**
     * Состояние после v4: у полей ещё есть `key`, у пресетов уже есть владелец. «Локация»
     * переименована прорабом, и на ней видно, что миграция переносит настройки, а не заводские
     * значения; у «Вида работ» и своего поля есть значения — на них проверяются CASCADE и RESTRICT.
     */
    private fun SupportSQLiteDatabase.seedV4Fixtures() {
        execSQL("INSERT INTO journals VALUES ('j1', 2026, 7, 'Июль', 0)")
        execSQL("INSERT INTO contractors VALUES ('c1', 'Г.П.', 'ГП', 0, 0, 0, 0)")
        execSQL("INSERT INTO records VALUES ('r-1', 'j1', 20000, 'c1', NULL, 0, 0)")
        insertFieldDef(BuiltInFields.LOCATION_ID, key = "location", orderIndex = 0, title = "Мои локации")
        insertFieldDef(BuiltInFields.WORK_ID, key = "work", orderIndex = 1)
        // Русское название давало один и тот же ключ `field` — та самая коллизия, из-за которой
        // колонка и убрана; здесь она нужна лишь как реалистичное содержимое строки.
        insertFieldDef("f-custom", key = "field", orderIndex = 2, title = "Объём")
        insertValue("r-1", BuiltInFields.WORK_ID, "Штукатурка")
        insertValue("r-1", "f-custom", "12 м²")
        execSQL(
            "INSERT INTO field_presets VALUES ('p-1', ?, 'К1', 0)",
            arrayOf(BuiltInFields.LOCATION_ID),
        )
    }

    /**
     * Состояние после v5: у записи один слот фото (`photoId`), колонки `photoId2` ещё нет. Двух
     * строк и двух фото хватает, чтобы проверить и перенос данных, и уникальность нового слота.
     */
    private fun SupportSQLiteDatabase.seedV5Fixtures() {
        execSQL("INSERT INTO journals VALUES ('j1', 2026, 7, 'Июль', 0)")
        execSQL("INSERT INTO contractors VALUES ('c1', 'Г.П.', 'ГП', 0, 0, 0, 0)")
        execSQL("INSERT INTO photos VALUES ('ph1', 100, 100, 1000, NULL, 0)")
        execSQL("INSERT INTO photos VALUES ('ph2', 100, 100, 1000, NULL, 0)")
        // v5-схема записи: 7 колонок, photoId2 ещё нет.
        execSQL("INSERT INTO records VALUES ('r-1', 'j1', 20000, 'c1', 'ph1', 0, 0)")
        execSQL("INSERT INTO records VALUES ('r-2', 'j1', 20000, 'c1', NULL, 0, 0)")
    }

    private fun SupportSQLiteDatabase.seedV6Fixtures() {
        execSQL("INSERT INTO journals VALUES ('j1', 2026, 7, 'Июль', 0)")
        execSQL("INSERT INTO contractors VALUES ('c1', 'Г.П.', 'ГП', 0, 0, 0, 0)")
        // Владелец будущих цветов: без поля цвету не на что ссылаться.
        execSQL(
            "INSERT INTO field_defs VALUES (?, 'ВИД РАБОТ', 'Вид работ', 1, 0, 1, 1, 1, 168, 0, 0, 0)",
            arrayOf(BuiltInFields.WORK_ID),
        )
        execSQL("INSERT INTO records (id, journalId, dateEpochDay, contractorId, createdAt, updatedAt) VALUES ('r-1', 'j1', 20000, 'c1', 0, 0)")
        execSQL("INSERT INTO records (id, journalId, dateEpochDay, contractorId, createdAt, updatedAt) VALUES ('r-2', 'j1', 20000, 'c1', 0, 0)")
        execSQL(
            "INSERT INTO record_values VALUES ('r-1', ?, 'Штукатурка')",
            arrayOf(BuiltInFields.WORK_ID),
        )
    }

    private fun SupportSQLiteDatabase.insertV1Record(id: String, location: String, work: String) {
        execSQL(
            "INSERT INTO records VALUES (?, 'j1', 20000, 'c1', ?, ?, NULL, 0, 0)",
            arrayOf(id, location, work),
        )
    }

    /** Вставка в схему ≤ v4, где у поля ещё была колонка `key`. */
    private fun SupportSQLiteDatabase.insertFieldDef(
        id: String,
        key: String,
        orderIndex: Long,
        title: String = key,
    ) {
        execSQL(
            "INSERT INTO field_defs VALUES (?, ?, ?, ?, ?, 0, 1, 0, 1, 100, 3, 1, 0)",
            arrayOf(id, key, title, title, orderIndex),
        )
    }

    private fun SupportSQLiteDatabase.insertValue(recordId: String, fieldId: String, value: String) {
        execSQL("INSERT INTO record_values VALUES (?, ?, ?)", arrayOf(recordId, fieldId, value))
    }

    /** Пресеты в порядке `orderIndex`: id, поле-владелец, значение. */
    private fun SupportSQLiteDatabase.queryPresets(): List<Triple<String, String, String>> =
        query("SELECT id, fieldId, code FROM field_presets ORDER BY orderIndex").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
                }
            }
        }

    private fun SupportSQLiteDatabase.columnsOf(table: String): List<String> =
        query("PRAGMA table_info($table)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            buildList { while (cursor.moveToNext()) add(cursor.getString(nameColumn)) }
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

    private fun SupportSQLiteDatabase.queryPairs(sql: String): Set<Pair<String, String>> =
        query(sql).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
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

        /**
         * Берётся из продакшн-кода, а не вписана числом: иначе следующий bump версии пришлось бы
         * помнить и повторить здесь, а забытый — оставил бы тесты зелёными на старой версии.
         */
        const val CURRENT_VERSION = APP_DATABASE_VERSION
    }
}
