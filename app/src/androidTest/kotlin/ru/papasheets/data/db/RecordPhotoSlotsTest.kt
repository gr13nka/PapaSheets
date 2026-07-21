package ru.papasheets.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Оба слота фото учитываются там, где фото собирают на удаление вместе с журналом.
 *
 * У фото нет reference counting: удаление журнала сносит записи каскадом, но файлы и строки фото
 * приходится удалять явно по списку из [ru.papasheets.data.db.dao.RecordDao.photoIdsForJournal]
 * (см. `DeleteJournalInteractor`). Пока запрос брал только `photoId`, фото второго слота оставалось
 * бы на диске сиротой навсегда — молча. Тест держит оба слота в этом списке.
 */
@RunWith(AndroidJUnit4::class)
class RecordPhotoSlotsTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        // Родители до записей — иначе внешние ключи не пройдут (в Room они включены при открытии).
        db.openHelper.writableDatabase.apply {
            execSQL("INSERT INTO journals VALUES ('j1', 2026, 7, 'Июль 2026', 0)")
            execSQL("INSERT INTO contractors VALUES ('c1', 'Петров', 'ПТР', 0, 0, 0, 0)")
            for (id in listOf("p1", "p2", "p3")) {
                execSQL("INSERT INTO photos VALUES ('$id', 100, 100, 1000, NULL, 0)")
            }
        }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun photoIdsForJournal_collectsBothSlots() = runBlocking {
        val dao = db.recordDao()
        // Запись с двумя фото, запись только со вторым слотом и запись вовсе без фото.
        db.openHelper.writableDatabase.apply {
            execSQL("INSERT INTO records VALUES ('r-both', 'j1', 20000, 'c1', 'p1', 'p2', 0, 0)")
            execSQL("INSERT INTO records VALUES ('r-slot2', 'j1', 20001, 'c1', NULL, 'p3', 0, 0)")
            execSQL("INSERT INTO records VALUES ('r-none', 'j1', 20002, 'c1', NULL, NULL, 0, 0)")
        }

        // Все три фото попадают в список — включая p3, лежащее только во втором слоте.
        assertEquals(setOf("p1", "p2", "p3"), dao.photoIdsForJournal("j1").toSet())
    }
}
