package ru.papasheets.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.testing.testField
import ru.papasheets.testing.testRecord

/**
 * Слой запроса (Э7): один фильтр на оба вида и предсказуемый порядок списка.
 *
 * Главная проверка этапа — [filterFeedsMatrixAndListWithTheSameRecords]: матрица и список обязаны
 * показывать одну выборку, и разъехаться они могут только если фильтрация окажется в двух местах.
 */
class JournalQueryTest {
    private val location = testField(id = "loc", orderIndex = 0)
    private val work = testField(id = "work", orderIndex = 1)
    private val fields = listOf(location, work)

    private fun contractor(id: String, name: String, orderIndex: Int = 0) = ContractorEntity(
        id = id, name = name, shortName = name.take(3), colorIndex = 0, orderIndex = orderIndex, createdAt = 0,
    )

    private val alpha = contractor("a", "Альфа", orderIndex = 0)
    private val beta = contractor("b", "Бета", orderIndex = 1)
    private val contractors = listOf(alpha, beta)

    private val r1 = testRecord(
        id = "r1", dateEpochDay = 10, contractorId = "a", createdAt = 1,
        values = mapOf("loc" to "Секция 1", "work" to "Штукатурка"),
    )
    private val r2 = testRecord(
        id = "r2", dateEpochDay = 11, contractorId = "b", createdAt = 2,
        values = mapOf("loc" to "Секция 2", "work" to "Кладка"),
    )
    private val r3 = testRecord(
        id = "r3", dateEpochDay = 12, contractorId = "a", createdAt = 3,
        values = mapOf("loc" to "Секция 1", "work" to "Кладка"),
    )

    /** Запись без локации — проверяет и «пустые в конец», и отбрасывание фильтром по этому полю. */
    private val noLocation = testRecord(
        id = "r4", dateEpochDay = 13, contractorId = "b", createdAt = 4,
        values = mapOf("work" to "Уборка"),
    )

    private val all = listOf(r1, r2, r3, noLocation)

    private fun ids(records: List<ru.papasheets.data.db.entity.RecordWithValues>) = records.map { it.record.id }

    // --- applyFilter ---

    @Test
    fun emptyFilterKeepsEveryRecord() {
        assertEquals(all, applyFilter(all, JournalFilter()))
    }

    @Test
    fun filtersByContractor() {
        val filtered = applyFilter(all, JournalFilter(contractorIds = setOf("a")))
        assertEquals(listOf("r1", "r3"), ids(filtered))
    }

    @Test
    fun filtersByFieldValue() {
        val filtered = applyFilter(all, JournalFilter(values = mapOf("loc" to setOf("Секция 1"))))
        assertEquals(listOf("r1", "r3"), ids(filtered))
    }

    @Test
    fun filterByFieldDropsRecordsWhereFieldIsEmpty() {
        val filtered = applyFilter(all, JournalFilter(values = mapOf("loc" to setOf("Секция 2"))))
        assertEquals(listOf("r2"), ids(filtered))
    }

    @Test
    fun filtersBySubstringAcrossAllFieldsIgnoringCase() {
        val filtered = applyFilter(all, JournalFilter(query = "кладк"))
        assertEquals(listOf("r2", "r3"), ids(filtered))
    }

    @Test
    fun conditionsCombineAsIntersection() {
        val filtered = applyFilter(
            all,
            JournalFilter(contractorIds = setOf("a"), values = mapOf("loc" to setOf("Секция 1")), query = "кладка"),
        )
        assertEquals(listOf("r3"), ids(filtered))
    }

    @Test
    fun emptyValueSetForFieldMeansAnyValue() {
        val filtered = applyFilter(all, JournalFilter(values = mapOf("loc" to emptySet())))
        assertEquals(all, filtered)
    }

    // --- sortRecords ---

    @Test
    fun sortsByDateInBothDirections() {
        assertEquals(
            listOf("r1", "r2", "r3", "r4"),
            ids(sortRecords(all, RecordSort(SortKey.Date, desc = false), contractors)),
        )
        assertEquals(
            listOf("r4", "r3", "r2", "r1"),
            ids(sortRecords(all, RecordSort(SortKey.Date, desc = true), contractors)),
        )
    }

    @Test
    fun sortsByContractorNameThenByDate() {
        assertEquals(
            listOf("r1", "r3", "r2", "r4"),
            ids(sortRecords(all, RecordSort(SortKey.Contractor), contractors)),
        )
    }

    @Test
    fun sortsByFieldValue() {
        // Кладка < Уборка < Штукатурка; внутри «Кладки» — по дате.
        assertEquals(
            listOf("r2", "r3", "r4", "r1"),
            ids(sortRecords(all, RecordSort(SortKey.Field("work")), contractors)),
        )
    }

    /** Вторичный ключ не разворачивается вместе с основным: внутри группы записи всегда хронологически. */
    @Test
    fun secondaryOrderStaysChronologicalWhenPrimaryReverses() {
        assertEquals(
            listOf("r2", "r4", "r1", "r3"),
            ids(sortRecords(all, RecordSort(SortKey.Contractor, desc = true), contractors)),
        )
    }

    @Test
    fun emptyValuesGoLastInBothDirections() {
        val ascending = ids(sortRecords(all, RecordSort(SortKey.Field("loc"), desc = false), contractors))
        val descending = ids(sortRecords(all, RecordSort(SortKey.Field("loc"), desc = true), contractors))
        assertEquals("r4", ascending.last())
        assertEquals("r4", descending.last())
        assertEquals(listOf("r1", "r3", "r2"), ascending.dropLast(1))
        assertEquals(listOf("r2", "r1", "r3"), descending.dropLast(1))
    }

    /** Равные ключи не должны переставлять записи местами — иначе список прыгает между перерисовками. */
    @Test
    fun equalKeysKeepStableChronologicalOrder() {
        val sameDay = listOf(
            testRecord(id = "x", dateEpochDay = 5, contractorId = "a", createdAt = 2, values = mapOf("loc" to "С")),
            testRecord(id = "y", dateEpochDay = 5, contractorId = "a", createdAt = 1, values = mapOf("loc" to "С")),
        )
        repeat(3) {
            assertEquals(
                listOf("y", "x"),
                ids(sortRecords(sameDay, RecordSort(SortKey.Field("loc")), contractors)),
            )
        }
    }

    @Test
    fun unknownFieldIdSortsAsAllEmptyInsteadOfFailing() {
        assertEquals(
            listOf("r1", "r2", "r3", "r4"),
            ids(sortRecords(all, RecordSort(SortKey.Field("нет-такого-поля")), contractors)),
        )
    }

    @Test
    fun unknownContractorIdSortsAsEmptyAndGoesLast() {
        val orphan = testRecord(id = "z", dateEpochDay = 1, contractorId = "снесённый", createdAt = 0)
        val sorted = ids(sortRecords(all + orphan, RecordSort(SortKey.Contractor), contractors))
        assertEquals("z", sorted.last())
    }

    // --- согласованность двух видов ---

    /**
     * Тот же фильтр обязан дать одинаковый набор записей матрице и списку. Матрица собирается через
     * [buildGridModel] и раскладывает записи по ячейкам, поэтому сравниваются именно множества
     * recordId, а не порядок: порядок у видов разный по определению, набор — обязан совпадать.
     */
    @Test
    fun filterFeedsMatrixAndListWithTheSameRecords() {
        val filters = listOf(
            JournalFilter(),
            JournalFilter(contractorIds = setOf("a")),
            JournalFilter(values = mapOf("loc" to setOf("Секция 1"))),
            JournalFilter(query = "кладка"),
            JournalFilter(contractorIds = setOf("b"), query = "уборка"),
        )
        for (filter in filters) {
            val filtered = applyFilter(all, filter)

            val grid = buildGridModel(filtered, contractors, fields, emptyMap(), sortDesc = false)
            val inMatrix = grid.rows.flatMap { row -> row.cells.filterNotNull().map { it.recordId } }.toSet()

            val inList = sortRecords(filtered, RecordSort(SortKey.Field("work")), contractors)
                .map { it.record.id }
                .toSet()

            assertEquals("фильтр $filter", inList, inMatrix)
        }
    }

    /**
     * Правило, по которому сохраняется место в матрице ([ru.papasheets.domain.LastPlace]): раскладка
     * считается «той же» только при виде матрицы, пустом фильтре и порядке дат по умолчанию — ровно с
     * этими значениями откроется следующий запуск. Любое отличие меняет состав или порядок строк, и
     * сохранённый вьюпорт указывал бы на другие дни.
     */
    @Test
    fun `default matrix layout is the one a cold start will show`() {
        assertTrue(JournalQuery().isDefaultMatrixLayout)
        assertFalse(JournalQuery(viewMode = ViewMode.LIST).isDefaultMatrixLayout)
        assertFalse(JournalQuery(filter = JournalFilter(contractorIds = setOf("a"))).isDefaultMatrixLayout)
        assertFalse(JournalQuery(sort = RecordSort(SortKey.Date, desc = true)).isDefaultMatrixLayout)
    }

    /**
     * Сортировка списка по чужому столбцу матрицу не перестраивает ([RecordSort.matrixDatesDesc]), и
     * запрещать сохранение места из-за неё было бы ложной тревогой.
     */
    @Test
    fun `list sort column does not disturb the matrix layout`() {
        assertTrue(JournalQuery(sort = RecordSort(SortKey.Field("work"), desc = true)).isDefaultMatrixLayout)
    }
}
