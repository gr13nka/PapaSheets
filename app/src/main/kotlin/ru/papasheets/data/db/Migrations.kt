package ru.papasheets.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ru.papasheets.exportkit.backup.BuiltInFields

/**
 * v1 → v2: набор полей записи становится данными (`field_defs` + `record_values`).
 *
 * **Миграция строго аддитивная — ни одного DROP или RENAME.** На телефоне лежит единственный
 * экземпляр рабочего журнала прораба без второй копии где бы то ни было, поэтому шаг устроен так,
 * чтобы худшим исходом была лишняя неиспользуемая таблица, а не потерянная запись: старые колонки
 * `records.locationCode`/`records.workText` остаются на месте нетронутыми и продолжают содержать
 * исходные данные. Если перенос окажется неполным, чинить можно по ним, а не по бэкапу.
 * Убрала их [Migrations.MIGRATION_2_3], когда стало видно, что всё перенеслось.
 *
 * Переносятся только непустые значения — см. инвариант «пустых значений не хранится»
 * в `RecordValueEntity`.
 */
object Migrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_1_2_DDL.forEach(db::execSQL)
            // Обязательно до переноса значений: FK на field_defs проверяется сразу при вставке.
            seedFieldDefsInV2Schema(db, System.currentTimeMillis())
            MIGRATION_1_2_COPY.forEach(db::execSQL)
        }
    }

    /**
     * v2 → v3: из `records` уходят страховочные колонки `locationCode`/`workText`.
     *
     * Дороже аддитивного шага: SQLite не умел `DROP COLUMN` до 3.35, а Android тащит собственный
     * SQLite, версия которого зависит от прошивки телефона, — рассчитывать на `DROP COLUMN` нельзя.
     * Поэтому классический обходной путь: новая таблица с нужной схемой, перелив данных без
     * лишних колонок, снос старой, переименование. Это единственная миграция в проекте, которая
     * пересоздаёт таблицу с данными.
     *
     * Позволить её себе можно ровно сейчас, пока приложением никто не пользуется и реальных данных
     * нет ни на одном устройстве: цена ошибки — переустановка. С первым же настоящим журналом
     * прораба та же правка станет операцией с необратимым риском ради косметики схемы, то есть
     * практически неоплатной. Отсюда правило: чистить схему — до выката, не после.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // `record_values.recordId` ссылается на `records(id)` с ON DELETE CASCADE, и подмена
            // таблицы через DROP выглядит смертельной: при включённых внешних ключах DROP TABLE
            // выполняет неявный DELETE, а тот унёс бы каскадом всё содержимое журнала.
            //
            // Спасает то, что во время миграции внешние ключи ВЫКЛЮЧЕНЫ: Room включает их строкой
            // `PRAGMA foreign_keys = ON` в `onOpen`, а фреймворк вызывает `onOpen` уже после
            // `onUpgrade`. На это же опираются автомиграции самого Room, пересоздающие таблицы.
            // Допущение неявное и несущее, поэтому закреплено тестом
            // `migratedDatabase_opensWithRoom`, который проверяет выживание значений именно на
            // продакшн-пути открытия, а не через MigrationTestHelper с его настройкой соединения.
            //
            // `defer_foreign_keys` оставлен подстраховкой на случай прогона миграции на соединении,
            // где ключи всё-таки включены: он откладывает проверку нарушений до коммита. Отменить
            // каскадные действия он НЕ может — если допущение выше однажды перестанет держаться,
            // спасёт не эта строка, а упавший тест.
            db.execSQL("PRAGMA defer_foreign_keys = TRUE")
            MIGRATION_2_3_DDL.forEach(db::execSQL)
        }
    }

    /**
     * v3 → v4: пресеты автодополнения перестают быть привязкой к одной «Локации» и переезжают на поле.
     *
     * `location_presets` знала ровно одно поле — встроенную локацию, — и своё поле прораба («Объём»)
     * получало только историю ввода. `field_presets.fieldId` снимает асимметрию: список готовых
     * значений появляется у любого поля, а «Локация» становится просто первым его пользователем.
     * Поэтому все существующие строки переносятся под [BuiltInFields.LOCATION_ID] — id встроенного
     * поля константен на любом устройстве, так что владелец находится без поиска.
     *
     * Новая таблица заводится рядом со старой, а не переименовывается: `location_presets` не имела
     * ни `fieldId`, ни внешнего ключа, и добавить их `ALTER TABLE` нельзя. Отложенная проверка FK
     * здесь не нужна (в отличие от [MIGRATION_2_3]): на `location_presets` никто не ссылается, а
     * `field_defs` со строкой «Локация» уже на месте с v2.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_3_4_DDL.forEach(db::execSQL)
        }
    }

    /**
     * v4 → v5: у определения поля больше нет машинного ключа `key`.
     *
     * Колонка появилась «на всякий случай» и не пригодилась: ни одного чтения по ней в коде не было,
     * встроенное поле опознаётся по константному `id`, а пользовательское — тоже по `id`. Зато
     * UNIQUE-индекс по `key` активно вредил. Ключ генерировался из названия с выбрасыванием
     * нелатинских символов, поэтому ЛЮБОЕ поле с русским названием получало один и тот же ключ
     * `field`; при импорте бэкапа с чужого устройства `@Upsert` натыкался на нарушение уникальности,
     * выполнял `UPDATE ... WHERE id = ?`, не находил строку (id-то другой) и молча возвращал ноль
     * затронутых строк. Определение поля терялось, а отчёт импорта показывал его добавленным.
     *
     * Пересоздание таблицы, а не `DROP COLUMN`: SQLite научился ронять колонки лишь в 3.35, а версия
     * SQLite на Android зависит от прошивки телефона. Приём тот же, что в [MIGRATION_2_3], и по тем же
     * причинам безопасный: во время миграции внешние ключи выключены (`onOpen` с
     * `PRAGMA foreign_keys = ON` фреймворк вызывает уже после `onUpgrade`), иначе неявный DELETE от
     * `DROP TABLE` унёс бы каскадом `field_presets` и упёрся бы в RESTRICT со стороны `record_values`.
     * `defer_foreign_keys` — та же подстраховка на случай включённых ключей: он откладывает проверку
     * до коммита, но каскад отменить не может. Настоящая гарантия — тест цепочки миграций.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("PRAGMA defer_foreign_keys = TRUE")
            MIGRATION_4_5_DDL.forEach(db::execSQL)
        }
    }

    /**
     * Полная цепочка в порядке версий. Существует затем, чтобы список миграций был ровно один:
     * [AppDatabase] и тест цепочки берут его отсюда, поэтому забытая в сборке миграция валит тест,
     * а не телефон прораба, пропустившего пару версий.
     */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
}

/**
 * DDL скопирован дословно из экспортированной схемы `app/schemas/.../2.json`, а не написан по памяти.
 * Room сверяет фактическую схему с ожидаемой по хешу при первом открытии БД: любое расхождение,
 * вплоть до порядка колонок, роняет приложение на identityHash. Правило — сначала правится entity
 * и пересобирается проект, потом `createSql` из свежего 2.json переносится сюда.
 */
internal val MIGRATION_1_2_DDL: List<String> = listOf(
    "CREATE TABLE IF NOT EXISTS `field_defs` (`id` TEXT NOT NULL, `key` TEXT NOT NULL, " +
        "`title` TEXT NOT NULL, `label` TEXT NOT NULL, `orderIndex` INTEGER NOT NULL, " +
        "`isArchived` INTEGER NOT NULL, `isBuiltIn` INTEGER NOT NULL, `isRequired` INTEGER NOT NULL, " +
        "`suggestFromHistory` INTEGER NOT NULL, `columnWidthDp` INTEGER NOT NULL, " +
        "`maxLines` INTEGER NOT NULL, `showAtCompactLod` INTEGER NOT NULL, " +
        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_field_defs_key` ON `field_defs` (`key`)",
    "CREATE TABLE IF NOT EXISTS `record_values` (`recordId` TEXT NOT NULL, `fieldId` TEXT NOT NULL, " +
        "`value` TEXT NOT NULL, PRIMARY KEY(`recordId`, `fieldId`), " +
        "FOREIGN KEY(`recordId`) REFERENCES `records`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
        "FOREIGN KEY(`fieldId`) REFERENCES `field_defs`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
    "CREATE INDEX IF NOT EXISTS `index_record_values_fieldId` ON `record_values` (`fieldId`)",
)

/**
 * Заводит встроенные поля в схеме v2 — той, что создаёт [MIGRATION_1_2_DDL], с колонкой `key`.
 *
 * Отдельно от `BuiltInFieldSeed`, хотя когда-то это была одна функция на оба пути. Разошлись они не
 * по недосмотру: сид чистой установки пишет строку ТЕКУЩЕЙ схемы, а миграция обязана писать строку
 * той, которую сама только что создала, — а с v5 у поля не стало `key`. Общая функция после этого
 * означала бы, что прошлое переписывается вместе с настоящим; замороженный шаг истории не должен
 * меняться от того, что схема поехала дальше.
 *
 * Значения по-прежнему берутся из [BuiltInFields], так что вторая их копия не заводится. `key = id`:
 * колонка требовала NOT NULL и UNIQUE, читать её было некому уже тогда, а [Migrations.MIGRATION_4_5]
 * её и вовсе уносит — годится любое уникальное значение, и `id` уникален по определению.
 *
 * Что обе ветки в итоге сходятся, проверяет `BuiltInFieldSeedTest` — сравнением на текущей версии,
 * то есть после всей цепочки, а не на v2.
 */
private fun seedFieldDefsInV2Schema(db: SupportSQLiteDatabase, createdAt: Long) {
    val sql = "INSERT INTO field_defs " +
        "(`id`, `key`, `title`, `label`, `orderIndex`, `isArchived`, `isBuiltIn`, `isRequired`, " +
        "`suggestFromHistory`, `columnWidthDp`, `maxLines`, `showAtCompactLod`, `createdAt`) " +
        "VALUES (?, ?, ?, ?, ?, 0, 1, ?, ?, ?, ?, ?, ?)"
    BuiltInFields.ALL.forEach { spec ->
        db.execSQL(
            sql,
            arrayOf<Any>(
                spec.id,
                spec.id,
                spec.title,
                spec.label,
                spec.orderIndex,
                if (spec.isRequired) 1 else 0,
                if (spec.suggestFromHistory) 1 else 0,
                spec.columnWidthDp,
                spec.maxLines,
                if (spec.showAtCompactLod) 1 else 0,
                createdAt,
            ),
        )
    }
}

/**
 * Перенос содержимого старых колонок. `TRIM(...) <> ''` отсекает не только пустые строки, но и
 * пробельные: до v2 форма могла сохранить «   » в необязательную локацию, а в `record_values`
 * такое значение было бы неотличимо от осмысленного.
 *
 * Значение переносится тоже обрезанным — раз пробелы уже признаны незначащими при решении
 * «пусто или нет», хранить их внутри значения непоследовательно: « К1 » и «К1» разъехались бы в
 * подсказках истории и в фильтрах как два разных значения. Форма после v2 обрезает при сохранении,
 * так что нормализуются и старые записи, и новые.
 */
internal val MIGRATION_1_2_COPY: List<String> = listOf(
    "INSERT INTO record_values (recordId, fieldId, value) " +
        "SELECT id, '${BuiltInFields.LOCATION_ID}', TRIM(locationCode) FROM records WHERE TRIM(locationCode) <> ''",
    "INSERT INTO record_values (recordId, fieldId, value) " +
        "SELECT id, '${BuiltInFields.WORK_ID}', TRIM(workText) FROM records WHERE TRIM(workText) <> ''",
)

/**
 * Пересоздание `records` без двух мёртвых колонок. `CREATE TABLE` скопирован дословно из
 * `app/schemas/.../3.json` — по тем же причинам, что и DDL v2 выше.
 *
 * Индексы перечислены явно и все: `DROP TABLE` уносит индексы вместе с таблицей, а Room сверяет
 * их по схеме наравне с колонками — забытый индекс уронит приложение на identityHash так же
 * надёжно, как забытая колонка.
 */
internal val MIGRATION_2_3_DDL: List<String> = listOf(
    "CREATE TABLE IF NOT EXISTS `_new_records` (`id` TEXT NOT NULL, `journalId` TEXT NOT NULL, " +
        "`dateEpochDay` INTEGER NOT NULL, `contractorId` TEXT NOT NULL, `photoId` TEXT, " +
        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
        "FOREIGN KEY(`journalId`) REFERENCES `journals`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
        "FOREIGN KEY(`contractorId`) REFERENCES `contractors`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , " +
        "FOREIGN KEY(`photoId`) REFERENCES `photos`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
    "INSERT INTO `_new_records` (`id`, `journalId`, `dateEpochDay`, `contractorId`, `photoId`, `createdAt`, `updatedAt`) " +
        "SELECT `id`, `journalId`, `dateEpochDay`, `contractorId`, `photoId`, `createdAt`, `updatedAt` FROM `records`",
    "DROP TABLE `records`",
    "ALTER TABLE `_new_records` RENAME TO `records`",
    "CREATE INDEX IF NOT EXISTS `index_records_journalId_dateEpochDay` ON `records` (`journalId`, `dateEpochDay`)",
    "CREATE INDEX IF NOT EXISTS `index_records_contractorId` ON `records` (`contractorId`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_records_photoId` ON `records` (`photoId`)",
)

/**
 * Перенос пресетов под их поле. `CREATE TABLE` и индекс скопированы дословно из
 * `app/schemas/.../4.json` — по тем же причинам, что и DDL выше: Room сверяет схему по хешу.
 *
 * Индекс по `fieldId` не украшение: автодополнение спрашивает пресеты по нему на каждое нажатие
 * клавиши, а Room всё равно потребовал бы его наличия под внешним ключом.
 */
internal val MIGRATION_3_4_DDL: List<String> = listOf(
    "CREATE TABLE IF NOT EXISTS `field_presets` (`id` TEXT NOT NULL, `fieldId` TEXT NOT NULL, " +
        "`code` TEXT NOT NULL, `orderIndex` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
        "FOREIGN KEY(`fieldId`) REFERENCES `field_defs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE INDEX IF NOT EXISTS `index_field_presets_fieldId` ON `field_presets` (`fieldId`)",
    "INSERT INTO field_presets (`id`, `fieldId`, `code`, `orderIndex`) " +
        "SELECT `id`, '${BuiltInFields.LOCATION_ID}', `code`, `orderIndex` FROM location_presets",
    "DROP TABLE location_presets",
)

/**
 * Пересоздание `field_defs` без колонки `key`. `CREATE TABLE` скопирован дословно из
 * `app/schemas/.../5.json` — по тем же причинам, что и DDL выше: Room сверяет схему по хешу.
 *
 * Восстанавливать после `DROP TABLE` нечего: единственным индексом таблицы был как раз UNIQUE по
 * `key`, а в 5.json у `field_defs` индексов не осталось вовсе.
 */
internal val MIGRATION_4_5_DDL: List<String> = listOf(
    "CREATE TABLE IF NOT EXISTS `_new_field_defs` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
        "`label` TEXT NOT NULL, `orderIndex` INTEGER NOT NULL, `isArchived` INTEGER NOT NULL, " +
        "`isBuiltIn` INTEGER NOT NULL, `isRequired` INTEGER NOT NULL, " +
        "`suggestFromHistory` INTEGER NOT NULL, `columnWidthDp` INTEGER NOT NULL, " +
        "`maxLines` INTEGER NOT NULL, `showAtCompactLod` INTEGER NOT NULL, " +
        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "INSERT INTO `_new_field_defs` (`id`, `title`, `label`, `orderIndex`, `isArchived`, `isBuiltIn`, " +
        "`isRequired`, `suggestFromHistory`, `columnWidthDp`, `maxLines`, `showAtCompactLod`, `createdAt`) " +
        "SELECT `id`, `title`, `label`, `orderIndex`, `isArchived`, `isBuiltIn`, " +
        "`isRequired`, `suggestFromHistory`, `columnWidthDp`, `maxLines`, `showAtCompactLod`, `createdAt` " +
        "FROM `field_defs`",
    "DROP TABLE `field_defs`",
    "ALTER TABLE `_new_field_defs` RENAME TO `field_defs`",
)
