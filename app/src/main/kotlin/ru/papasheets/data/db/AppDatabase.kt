package ru.papasheets.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import ru.papasheets.data.DefaultSeed
import ru.papasheets.data.db.dao.ContractorDao
import ru.papasheets.data.db.dao.FieldDefDao
import ru.papasheets.data.db.dao.JournalDao
import ru.papasheets.data.db.dao.LocationDao
import ru.papasheets.data.db.dao.PhotoDao
import ru.papasheets.data.db.dao.RecordDao
import ru.papasheets.data.db.dao.RecordValueDao
import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.data.db.entity.FieldDefEntity
import ru.papasheets.data.db.entity.JournalEntity
import ru.papasheets.data.db.entity.LocationPresetEntity
import ru.papasheets.data.db.entity.PhotoEntity
import ru.papasheets.data.db.entity.RecordEntity
import ru.papasheets.data.db.entity.RecordValueEntity

@Database(
    entities = [
        JournalEntity::class,
        ContractorEntity::class,
        RecordEntity::class,
        LocationPresetEntity::class,
        PhotoEntity::class,
        FieldDefEntity::class,
        RecordValueEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun journalDao(): JournalDao
    abstract fun recordDao(): RecordDao
    abstract fun contractorDao(): ContractorDao
    abstract fun locationDao(): LocationDao
    abstract fun photoDao(): PhotoDao
    abstract fun fieldDefDao(): FieldDefDao
    abstract fun recordValueDao(): RecordValueDao

    companion object {
        private const val DB_NAME = "papasheets.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                .addCallback(DefaultSeed.callback())
                .addMigrations(Migrations.MIGRATION_1_2)
                // Никакого destructive-fallback: в релизе это была бы молчаливая потеря данных при
                // первом же расхождении. Любое изменение схемы = bump version + настоящий Migration
                // (иначе Room упадёт на несовпадении hash — и это правильно, лучше явный отказ,
                // чем тихо стёртый журнал прораба).
                .build()
    }
}
