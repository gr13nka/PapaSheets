package ru.papasheets.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import ru.papasheets.data.DefaultSeed
import ru.papasheets.data.db.dao.ContractorDao
import ru.papasheets.data.db.dao.FieldDefDao
import ru.papasheets.data.db.dao.FieldPresetDao
import ru.papasheets.data.db.dao.JournalDao
import ru.papasheets.data.db.dao.PhotoDao
import ru.papasheets.data.db.dao.RecordDao
import ru.papasheets.data.db.dao.RecordValueDao
import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.data.db.entity.FieldDefEntity
import ru.papasheets.data.db.entity.FieldPresetEntity
import ru.papasheets.data.db.entity.JournalEntity
import ru.papasheets.data.db.entity.PhotoEntity
import ru.papasheets.data.db.entity.RecordEntity
import ru.papasheets.data.db.entity.RecordValueEntity

/**
 * Версия схемы отдельной константой, а не числом внутри `@Database`: её должны видеть тесты
 * миграций, чтобы «текущая версия» была одна на весь проект. Само значение `@Database` прочитать
 * рефлексией нельзя — аннотации Room не доживают до рантайма.
 *
 * Поднимать её можно только вместе с новой [Migration] в [Migrations.ALL] — см. docs/evolution.md.
 */
const val APP_DATABASE_VERSION = 6

@Database(
    entities = [
        JournalEntity::class,
        ContractorEntity::class,
        RecordEntity::class,
        FieldPresetEntity::class,
        PhotoEntity::class,
        FieldDefEntity::class,
        RecordValueEntity::class,
    ],
    version = APP_DATABASE_VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun journalDao(): JournalDao
    abstract fun recordDao(): RecordDao
    abstract fun contractorDao(): ContractorDao
    abstract fun fieldPresetDao(): FieldPresetDao
    abstract fun photoDao(): PhotoDao
    abstract fun fieldDefDao(): FieldDefDao
    abstract fun recordValueDao(): RecordValueDao

    companion object {
        private const val DB_NAME = "papasheets.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                .addCallback(DefaultSeed.callback())
                // Вся цепочка, а не только последний шаг: на устройстве может стоять сборка любой
                // давности, и Room должен уметь довести её до текущей версии через все промежуточные.
                .addMigrations(*Migrations.ALL)
                // Никакого destructive-fallback: в релизе это была бы молчаливая потеря данных при
                // первом же расхождении. Любое изменение схемы = bump version + настоящий Migration
                // (иначе Room упадёт на несовпадении hash — и это правильно, лучше явный отказ,
                // чем тихо стёртый журнал прораба).
                .build()
    }
}
