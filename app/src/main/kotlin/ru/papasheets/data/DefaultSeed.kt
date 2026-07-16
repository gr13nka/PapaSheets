package ru.papasheets.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

/** Пять реальных подрядчиков из бумажного журнала — заводятся один раз при создании БД. */
object DefaultSeed {
    private data class SeedContractor(val name: String, val shortName: String)

    private val contractors = listOf(
        SeedContractor(name = "Г.П.", shortName = "ГП"),
        SeedContractor(name = "Оконщики", shortName = "Окн"),
        SeedContractor(name = "Хамамщики", shortName = "Хам"),
        SeedContractor(name = "Плиточники", shortName = "Плт"),
        SeedContractor(name = "СВК", shortName = "СВК"),
    )

    fun callback(): RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            val now = System.currentTimeMillis()
            contractors.forEachIndexed { index, contractor ->
                val values = ContentValues().apply {
                    put("id", UUID.randomUUID().toString())
                    put("name", contractor.name)
                    put("shortName", contractor.shortName)
                    put("colorIndex", index)
                    put("orderIndex", index)
                    put("isArchived", 0)
                    put("createdAt", now)
                }
                db.insert("contractors", SQLiteDatabase.CONFLICT_ABORT, values)
            }
        }
    }
}
