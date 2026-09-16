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
 * Правила, которые обязан держать [FieldRepository], а не экран настройки группы: что можно удалить
 * и каким получается новое или отредактированное поле.
 *
 * Проверяются на подставном DAO, а не на настоящем Room: правила — это решения репозитория, и
 * настоящий SQLite добавил бы к тесту только время старта эмулятора.
 */
class FieldRepositoryTest {

    private class FakeDao(initial: List<FieldDefEntity> = emptyList()) : FieldDefDao {
        val rows = initial.toMutableList()
        override fun observeForJournal(journalId: String) = flowOf(rows.filter { it.journalId == journalId }.sortedBy { it.orderIndex })
        override suspend fun getForJournal(journalId: String) = rows.filter { it.journalId == journalId }.sortedBy { it.orderIndex }
        override suspend fun deleteForJournal(journalId: String) { rows.removeAll { it.journalId == journalId } }
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
        title: String = id,
        orderIndex: Int = 0,
        isBuiltIn: Boolean = false,
    ) = FieldDefEntity(
        id = id,
        title = title,
        label = title,
        orderIndex = orderIndex,
        isArchived = false,
        isBuiltIn = isBuiltIn,
        isRequired = false,
        suggestFromHistory = true,
        columnWidthDp = 100,
        maxLines = 1,
        showAtCompactLod = false,
        createdAt = 0,
     journalId = "j1",)

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

        val id = runBlocking { repository.create("j1", draft("Объём")) }

        val created = dao.rows.single { it.label == "Объём" }
        assertEquals(id, created.id)
        assertEquals(2, created.orderIndex)
    }

    /** Новое поле — всегда своё: встроенность назначает не вызывающий, а сид и миграция. */
    @Test
    fun `a new field is never built in`() {
        val dao = FakeDao()
        val repository = FieldRepository(dao)

        runBlocking { repository.create("j1", draft("Объём")) }

        assertTrue(dao.rows.none { it.isBuiltIn })
    }

    /**
     * Одинаково названные поля остаются разными строками. Различает их `id`, и ничего кроме него:
     * машинного ключа у поля нет — см. заметку про встроенные поля в `docs/evolution.md`.
     */
    @Test
    fun `fields with identical labels stay distinct`() {
        val dao = FakeDao()
        val repository = FieldRepository(dao)

        runBlocking {
            repository.create("j1", draft("Объём"))
            repository.create("j1", draft("Объём"))
            repository.create("j1", draft("Объём"))
        }

        assertEquals(3, dao.rows.map { it.id }.toSet().size)
    }

    @Test
    fun `an unused custom field is deleted`() {
        val dao = FakeDao(listOf(field("f-volume")))
        val repository = FieldRepository(dao)

        val outcome = runBlocking { repository.delete("f-volume") }

        assertEquals(FieldDeleteOutcome.Deleted, outcome)
        assertTrue(dao.rows.isEmpty())
    }

    /** Удаление заполненного поля стёрло бы содержимое записей — остаётся архивация. */
    @Test
    fun `a field with values is refused and stays`() {
        val dao = FakeDao(listOf(field("f-volume"))).apply { valueCounts = mapOf("f-volume" to 42) }
        val repository = FieldRepository(dao)

        val outcome = runBlocking { repository.delete("f-volume") }

        assertEquals(FieldDeleteOutcome.InUse(42), outcome)
        assertEquals(1, dao.rows.size)
    }

    /** Встроенное поле не удаляется даже пустым: сид и бэкап всё равно вернули бы его обратно. */
    @Test
    fun `an unused legacy starter field can be deleted`() {
        val dao = FakeDao(listOf(field(BuiltInFields.LOCATION_ID, title = "Локация", isBuiltIn = true)))
        val repository = FieldRepository(dao)

        val outcome = runBlocking { repository.delete(BuiltInFields.LOCATION_ID) }

        assertEquals(FieldDeleteOutcome.Deleted, outcome)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `reorder renumbers orderIndex by position`() {
        val dao = FakeDao(listOf(field("a", orderIndex = 0), field("b", orderIndex = 1), field("c", orderIndex = 2)))
        val repository = FieldRepository(dao)

        runBlocking { repository.reorder(listOf("c", "a", "b")) }

        assertEquals(listOf("c", "a", "b"), dao.getAllSortedIds())
    }

    /** Неизвестный id (поле удалено параллельно) молча пропускается, а не валит весь reorder. */
    @Test
    fun `reorder skips ids that no longer exist`() {
        val dao = FakeDao(listOf(field("a", orderIndex = 0), field("b", orderIndex = 1)))
        val repository = FieldRepository(dao)

        runBlocking { repository.reorder(listOf("b", "gone", "a")) }

        assertEquals(listOf("b", "a"), dao.getAllSortedIds())
    }

    /** `id` и место в порядке колонок — не то, чем распоряжается экран настройки группы: правка их не трогает. */
    @Test
    fun `edit changes one property and keeps the rest`() {
        val dao = FakeDao(listOf(field("f-volume", orderIndex = 3)))
        val repository = FieldRepository(dao)

        runBlocking { repository.edit("f-volume") { it.copy(label = "Объём бетона") } }

        val row = dao.rows.single()
        assertEquals("f-volume", row.id)
        assertEquals(3, row.orderIndex)
        assertEquals("Объём бетона", row.label)
        // Ничего, кроме label, правка не просила менять — title/ширина/строки остаются как были.
        assertEquals("f-volume", row.title)
        assertEquals(100, row.columnWidthDp)
        assertEquals(1, row.maxLines)
    }

    @Test
    fun `edit on a missing id does nothing`() {
        val dao = FakeDao(listOf(field("a")))
        val repository = FieldRepository(dao)

        runBlocking { repository.edit("missing") { it.copy(label = "x") } }

        assertEquals(1, dao.rows.size)
        assertEquals("a", dao.rows.single().label)
    }

    private fun FakeDao.getAllSortedIds(): List<String> = rows.sortedBy { it.orderIndex }.map { it.id }
}
