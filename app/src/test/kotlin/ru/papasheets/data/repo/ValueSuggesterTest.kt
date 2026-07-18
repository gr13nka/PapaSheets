package ru.papasheets.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.papasheets.data.db.dao.FieldPresetDao
import ru.papasheets.data.db.dao.RecordValueDao
import ru.papasheets.data.db.entity.FieldPresetEntity
import ru.papasheets.data.db.entity.RecordValueEntity
import ru.papasheets.exportkit.backup.BuiltInFields

private const val VOLUME_FIELD = "f-volume"

class ValueSuggesterTest {

    /** История по полям: DAO уже отдаёт её отфильтрованной по префиксу и отсортированной по недавности. */
    private class FakeValueDao(private val history: Map<String, List<String>>) : RecordValueDao {
        override suspend fun getAll(): List<RecordValueEntity> = emptyList()
        override suspend fun deleteForRecord(recordId: String) = Unit
        override suspend fun upsertAll(values: List<RecordValueEntity>) = Unit
        override suspend fun suggestFromHistory(fieldId: String, prefix: String): List<String> =
            history[fieldId].orEmpty().filter { it.startsWith(prefix, ignoreCase = true) }
    }

    private class FakePresetDao(private val byField: Map<String, List<String>>) : FieldPresetDao {
        private fun rowsOf(fieldId: String): List<FieldPresetEntity> =
            byField[fieldId].orEmpty().mapIndexed { index, code ->
                FieldPresetEntity(id = "$fieldId-$index", fieldId = fieldId, code = code, orderIndex = index)
            }

        override fun observeFor(fieldId: String): Flow<List<FieldPresetEntity>> = flowOf(rowsOf(fieldId))
        override suspend fun presetsFor(fieldId: String): List<FieldPresetEntity> = rowsOf(fieldId)
        override suspend fun getAll(): List<FieldPresetEntity> = byField.keys.flatMap { rowsOf(it) }
        override suspend fun insert(preset: FieldPresetEntity) = Unit
        override suspend fun delete(preset: FieldPresetEntity) = Unit
        override suspend fun upsertFromBackup(preset: FieldPresetEntity) = Unit
    }

    private fun suggester(
        history: Map<String, List<String>> = emptyMap(),
        presets: Map<String, List<String>> = emptyMap(),
    ) = ValueSuggester(FakeValueDao(history), FakePresetDao(presets))

    @Test
    fun `history of any field feeds its suggestions`() {
        val suggester = suggester(history = mapOf(VOLUME_FIELD to listOf("12 м²", "12 шт")))

        assertEquals(listOf("12 м²", "12 шт"), runBlocking { suggester.suggest(VOLUME_FIELD, "12") })
    }

    @Test
    fun `presets go first and history follows`() {
        val suggester = suggester(
            history = mapOf(BuiltInFields.LOCATION_ID to listOf("1-77")),
            presets = mapOf(BuiltInFields.LOCATION_ID to listOf("1-01", "1-02")),
        )

        val result = runBlocking { suggester.suggest(BuiltInFields.LOCATION_ID, "1-") }

        assertEquals(listOf("1-01", "1-02", "1-77"), result)
    }

    @Test
    fun `a value present both as a preset and in history appears once`() {
        val suggester = suggester(
            history = mapOf(BuiltInFields.LOCATION_ID to listOf("1-01", "1-77")),
            presets = mapOf(BuiltInFields.LOCATION_ID to listOf("1-01")),
        )

        val result = runBlocking { suggester.suggest(BuiltInFields.LOCATION_ID, "1-") }

        assertEquals(listOf("1-01", "1-77"), result)
    }

    /**
     * Своё поле прораба получает пресеты на тех же правах, что и встроенная «Локация».
     *
     * До v4 пресеты были привязаны к локации, и «Объём» довольствовался только историей ввода. Это
     * и была та асимметрия, ради снятия которой заведён `field_presets.fieldId`.
     */
    @Test
    fun `presets of a custom field are mixed in the same way`() {
        val suggester = suggester(
            history = mapOf(VOLUME_FIELD to listOf("12 шт")),
            presets = mapOf(VOLUME_FIELD to listOf("12 м²")),
        )

        assertEquals(listOf("12 м²", "12 шт"), runBlocking { suggester.suggest(VOLUME_FIELD, "12") })
    }

    /** Пресеты не протекают между полями: каждое поле видит только свои. */
    @Test
    fun `presets of another field are not offered`() {
        val suggester = suggester(
            history = mapOf(VOLUME_FIELD to listOf("1-99")),
            presets = mapOf(BuiltInFields.LOCATION_ID to listOf("1-01")),
        )

        assertEquals(listOf("1-99"), runBlocking { suggester.suggest(VOLUME_FIELD, "1-") })
    }

    @Test
    fun `presets are filtered by the typed prefix`() {
        val suggester = suggester(presets = mapOf(BuiltInFields.LOCATION_ID to listOf("1-01", "2-03")))

        assertEquals(listOf("2-03"), runBlocking { suggester.suggest(BuiltInFields.LOCATION_ID, "2") })
    }
}
