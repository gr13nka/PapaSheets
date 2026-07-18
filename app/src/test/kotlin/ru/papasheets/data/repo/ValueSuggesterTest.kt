package ru.papasheets.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.papasheets.data.db.dao.LocationDao
import ru.papasheets.data.db.dao.RecordValueDao
import ru.papasheets.data.db.entity.LocationPresetEntity
import ru.papasheets.data.db.entity.RecordValueEntity
import ru.papasheets.exportkit.backup.BuiltInFields

class ValueSuggesterTest {

    /** История по полям: DAO уже отдаёт её отфильтрованной по префиксу и отсортированной по недавности. */
    private class FakeValueDao(private val history: Map<String, List<String>>) : RecordValueDao {
        override suspend fun getAll(): List<RecordValueEntity> = emptyList()
        override suspend fun deleteForRecord(recordId: String) = Unit
        override suspend fun upsertAll(values: List<RecordValueEntity>) = Unit
        override suspend fun suggestFromHistory(fieldId: String, prefix: String): List<String> =
            history[fieldId].orEmpty().filter { it.startsWith(prefix, ignoreCase = true) }
    }

    private class FakeLocationDao(private val codes: List<String>) : LocationDao {
        override fun observePresets(): Flow<List<LocationPresetEntity>> =
            flowOf(codes.mapIndexed { index, code -> LocationPresetEntity(id = "p$index", code = code, orderIndex = index) })

        override suspend fun insert(preset: LocationPresetEntity) = Unit
        override suspend fun delete(preset: LocationPresetEntity) = Unit
        override suspend fun upsertFromBackup(preset: LocationPresetEntity) = Unit
    }

    private fun suggester(history: Map<String, List<String>> = emptyMap(), presets: List<String> = emptyList()) =
        ValueSuggester(FakeValueDao(history), FakeLocationDao(presets))

    @Test
    fun `history of any field feeds its suggestions`() {
        val suggester = suggester(history = mapOf("volume" to listOf("12 м²", "12 шт")))

        assertEquals(listOf("12 м²", "12 шт"), runBlocking { suggester.suggest("volume", "12") })
    }

    @Test
    fun `presets go first and history follows`() {
        val suggester = suggester(
            history = mapOf(BuiltInFields.LOCATION_ID to listOf("1-77")),
            presets = listOf("1-01", "1-02"),
        )

        val result = runBlocking { suggester.suggest(BuiltInFields.LOCATION_ID, "1-") }

        assertEquals(listOf("1-01", "1-02", "1-77"), result)
    }

    @Test
    fun `a value present both as a preset and in history appears once`() {
        val suggester = suggester(
            history = mapOf(BuiltInFields.LOCATION_ID to listOf("1-01", "1-77")),
            presets = listOf("1-01"),
        )

        val result = runBlocking { suggester.suggest(BuiltInFields.LOCATION_ID, "1-") }

        assertEquals(listOf("1-01", "1-77"), result)
    }

    @Test
    fun `presets are mixed into the location field only`() {
        // ВРЕМЕННАЯ АСИММЕТРИЯ: пресеты пока привязаны к локации, а не к полю (см. ValueSuggester).
        val suggester = suggester(history = mapOf("volume" to listOf("1-99")), presets = listOf("1-01"))

        assertEquals(listOf("1-99"), runBlocking { suggester.suggest("volume", "1-") })
    }

    @Test
    fun `presets are filtered by the typed prefix`() {
        val suggester = suggester(presets = listOf("1-01", "2-03"))

        assertEquals(listOf("2-03"), runBlocking { suggester.suggest(BuiltInFields.LOCATION_ID, "2") })
    }
}
