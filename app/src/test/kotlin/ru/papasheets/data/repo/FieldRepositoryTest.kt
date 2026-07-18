package ru.papasheets.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.papasheets.data.db.dao.FieldDefDao
import ru.papasheets.data.db.entity.FieldDefEntity
import ru.papasheets.exportkit.backup.BuiltInFields

/**
 * Правила, которые обязан держать [FieldRepository], а не экран полей: что можно удалить, каким
 * получается новое поле и почему его `key` не может совпасть с чужим.
 *
 * Проверяются на подставном DAO, а не на настоящем Room: правила — это решения репозитория, и
 * настоящий SQLite добавил бы к тесту только время старта эмулятора.
 */
class FieldRepositoryTest {

    private class FakeDao(initial: List<FieldDefEntity> = emptyList()) : FieldDefDao {
        val rows = initial.toMutableList()
        var valueCounts: Map<String, Int> = emptyMap()

        override fun observeAll(): Flow<List<FieldDefEntity>> = flowOf(rows.sortedBy { it.orderIndex })
        override suspend fun getAll(): List<FieldDefEntity> = rows.sortedBy { it.orderIndex }
        override suspend fun valueCount(fieldId: String): Int = valueCounts[fieldId] ?: 0
        override suspend fun insert(field: FieldDefEntity) { rows += field }
        override suspend fun update(field: FieldDefEntity) {
            rows[rows.indexOfFirst { it.id == field.id }] = field
        }
        override suspend fun updateAll(fields: List<FieldDefEntity>) = fields.forEach { update(it) }
        override suspend fun delete(field: FieldDefEntity) { rows.removeIf { it.id == field.id } }
        override suspend fun upsertFromBackup(field: FieldDefEntity) = Unit
    }

    private fun field(
        id: String,
        key: String = id,
        orderIndex: Int = 0,
        isBuiltIn: Boolean = false,
    ) = FieldDefEntity(
        id = id,
        key = key,
        title = key,
        label = key,
        orderIndex = orderIndex,
        isArchived = false,
        isBuiltIn = isBuiltIn,
        isRequired = false,
        suggestFromHistory = true,
        columnWidthDp = 100,
        maxLines = 1,
        showAtCompactLod = false,
        createdAt = 0,
    )

    private fun draft(label: String) = FieldDraft(
        title = label,
        label = label,
        columnWidthDp = 100,
        maxLines = 1,
        isRequired = false,
        suggestFromHistory = true,
        showAtCompactLod = false,
    )

    @Test
    fun `a new field goes to the end of the column order`() {
        val dao = FakeDao(listOf(field("a", orderIndex = 0), field("b", orderIndex = 1)))
        val repository = FieldRepository(dao)

        runBlocking { repository.create(draft("Объём")) }

        assertEquals(2, dao.rows.single { it.label == "Объём" }.orderIndex)
    }

    /** Новое поле — всегда своё: встроенность назначает не вызывающий, а сид и миграция. */
    @Test
    fun `a new field is never built in`() {
        val dao = FakeDao()
        val repository = FieldRepository(dao)

        runBlocking { repository.create(draft("Объём")) }

        assertTrue(dao.rows.none { it.isBuiltIn })
    }

    /**
     * `field_defs.key` под UNIQUE-индексом, поэтому совпадение ключей было бы не «неаккуратностью»,
     * а отказом вставки. Русские названия латиницы не содержат, так что все свои поля приходят к
     * одной и той же основе — и разойтись обязаны сами.
     */
    @Test
    fun `keys of fields with identical labels do not collide`() {
        val dao = FakeDao()
        val repository = FieldRepository(dao)

        runBlocking {
            repository.create(draft("Объём"))
            repository.create(draft("Объём"))
            repository.create(draft("Объём"))
        }

        assertEquals(3, dao.rows.map { it.key }.toSet().size)
    }

    /** Не должен столкнуться и с ключом встроенного поля, у которого он осмысленный и занят. */
    @Test
    fun `a generated key does not collide with a built-in one`() {
        val dao = FakeDao(listOf(field(BuiltInFields.LOCATION_ID, key = "location", isBuiltIn = true)))
        val repository = FieldRepository(dao)

        runBlocking { repository.create(draft("location")) }

        assertEquals(2, dao.rows.map { it.key }.toSet().size)
    }

    @Test
    fun `an unused custom field is deleted`() {
        val dao = FakeDao(listOf(field("f-volume")))
        val repository = FieldRepository(dao)

        val outcome = runBlocking { repository.delete(dao.rows.single()) }

        assertEquals(FieldDeleteOutcome.Deleted, outcome)
        assertTrue(dao.rows.isEmpty())
    }

    /** Удаление заполненного поля стёрло бы содержимое записей — остаётся архивация. */
    @Test
    fun `a field with values is refused and stays`() {
        val dao = FakeDao(listOf(field("f-volume"))).apply { valueCounts = mapOf("f-volume" to 42) }
        val repository = FieldRepository(dao)

        val outcome = runBlocking { repository.delete(dao.rows.single()) }

        assertEquals(FieldDeleteOutcome.InUse(42), outcome)
        assertEquals(1, dao.rows.size)
    }

    /** Встроенное поле не удаляется даже пустым: сид и бэкап всё равно вернули бы его обратно. */
    @Test
    fun `a built-in field is refused even with no values`() {
        val dao = FakeDao(listOf(field(BuiltInFields.LOCATION_ID, key = "location", isBuiltIn = true)))
        val repository = FieldRepository(dao)

        val outcome = runBlocking { repository.delete(dao.rows.single()) }

        assertEquals(FieldDeleteOutcome.BuiltIn, outcome)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `reorder renumbers orderIndex by position`() {
        val dao = FakeDao(listOf(field("a", orderIndex = 0), field("b", orderIndex = 1), field("c", orderIndex = 2)))
        val repository = FieldRepository(dao)

        runBlocking { repository.reorder(listOf(dao.rows[2], dao.rows[0], dao.rows[1])) }

        assertEquals(listOf("c", "a", "b"), dao.getAllSortedIds())
    }

    /** `key` — стабильное машинное имя колонки: правка названий его не трогает. */
    @Test
    fun `update changes labels but never the key`() {
        val dao = FakeDao(listOf(field("f-volume", key = "field")))
        val repository = FieldRepository(dao)

        runBlocking { repository.update(dao.rows.single(), draft("Объём бетона")) }

        assertEquals("field", dao.rows.single().key)
        assertEquals("Объём бетона", dao.rows.single().label)
    }

    private fun FakeDao.getAllSortedIds(): List<String> = rows.sortedBy { it.orderIndex }.map { it.id }
}
