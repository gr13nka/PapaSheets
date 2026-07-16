package ru.papasheets.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import ru.papasheets.data.DefaultSeed
import ru.papasheets.data.db.dao.ContractorDao
import ru.papasheets.data.db.dao.JournalDao
import ru.papasheets.data.db.dao.LocationDao
import ru.papasheets.data.db.dao.RecordDao
import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.data.db.entity.JournalEntity
import ru.papasheets.data.db.entity.LocationPresetEntity
import ru.papasheets.data.db.entity.RecordEntity

@Database(
    entities = [
        JournalEntity::class,
        ContractorEntity::class,
        RecordEntity::class,
        LocationPresetEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun journalDao(): JournalDao
    abstract fun recordDao(): RecordDao
    abstract fun contractorDao(): ContractorDao
    abstract fun locationDao(): LocationDao

    companion object {
        private const val DB_NAME = "papasheets.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                .addCallback(DefaultSeed.callback())
                .build()
    }
}
