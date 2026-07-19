package ru.papasheets.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import ru.papasheets.data.db.BuiltInFieldSeed
import ru.papasheets.data.db.entity.ContractorEntity
import java.util.UUID

/**
 * Начальное содержимое БД, создаваемой с нуля: пять реальных подрядчиков из бумажного журнала
 * и встроенные определения полей.
 *
 * `onCreate` вызывается только при создании БД и никогда при обновлении, поэтому устройство,
 * пришедшее с v1, получает те же поля из `Migrations.MIGRATION_1_2`. Чтобы обе ветки давали
 * одинаковый результат не по дисциплине, а структурно, поля в обеих заводит общий
 * `BuiltInFieldSeed` — здесь их значения не дублируются.
 */
object DefaultSeed {
    private data class SeedContractor(val name: String, val shortName: String)

    /**
     * Стоит ли на устройстве нетронутый заводской пул подрядчиков — то есть можно ли снести его
     * целиком, восстанавливая бэкап (см. `ImportInteractor`).
     *
     * Сравнение идёт по содержимому, а не по «пусто ли ещё в журнале»: id у сида случайные и с id из
     * бэкапа не совпадут никогда, поэтому не снесённая заглушка задвоила бы тех же подрядчиков под
     * новыми именами. Но стоит прорабу переименовать, добавить, переставить или заархивировать хоть
     * одного — это уже его пул, а не заглушка, и молча стирать его нельзя, даже если ни одной записи
     * он ещё не завёл. Отличить одно от другого можно только сверив набор с заводским, что здесь и
     * происходит: содержимое сида живёт в одном месте, и вторая его копия для сравнения не заводится.
     */
    fun isUntouchedSeed(existing: List<ContractorEntity>): Boolean =
        existing.size == contractors.size &&
            existing.sortedBy { it.orderIndex }.zip(contractors).withIndex().all { (index, pair) ->
                val (actual, seed) = pair
                actual.name == seed.name && actual.shortName == seed.shortName &&
                    actual.colorIndex == index && actual.orderIndex == index && !actual.isArchived
            }

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
            BuiltInFieldSeed.insertInto(db, now)
        }
    }
}
