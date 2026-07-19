package ru.papasheets.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.papasheets.data.db.entity.ContractorEntity

/**
 * Узнавание нетронутой заводской заглушки — единственное, что отделяет «снести подрядчиков при
 * импорте» от «стереть прорабу его собственный список».
 *
 * Заглушка заводится со случайными UUID, поэтому опознать её можно только по содержимому. Тесты
 * ниже перечисляют способы её тронуть: каждый из них обязан выключить снос.
 */
class DefaultSeedTest {

    /** Ровно то, что кладёт в БД `DefaultSeed.callback()`; id случайны и в сравнении не участвуют. */
    private fun seeded(): List<ContractorEntity> = listOf(
        "Г.П." to "ГП",
        "Оконщики" to "Окн",
        "Хамамщики" to "Хам",
        "Плиточники" to "Плт",
        "СВК" to "СВК",
    ).mapIndexed { index, (name, shortName) ->
        ContractorEntity(
            id = "random-uuid-$index",
            name = name,
            shortName = shortName,
            colorIndex = index,
            orderIndex = index,
            isArchived = false,
            createdAt = 1_700_000_000_000,
        )
    }

    @Test
    fun `the untouched factory pool is recognised`() {
        assertTrue(DefaultSeed.isUntouchedSeed(seeded()))
    }

    /** Порядок в списке ничего не значит — сверяется содержимое, отсортированное по orderIndex. */
    @Test
    fun `the pool is recognised regardless of row order`() {
        assertTrue(DefaultSeed.isUntouchedSeed(seeded().reversed()))
    }

    @Test
    fun `an empty pool is not the factory seed`() {
        assertFalse(DefaultSeed.isUntouchedSeed(emptyList()))
    }

    @Test
    fun `a contractor added by the user cancels the reset`() {
        val own = seeded().first().copy(id = "own", name = "Кровельщики", shortName = "Крв", orderIndex = 5, colorIndex = 5)

        assertFalse(DefaultSeed.isUntouchedSeed(seeded() + own))
    }

    @Test
    fun `a renamed contractor cancels the reset`() {
        val renamed = seeded().toMutableList().apply { this[1] = this[1].copy(name = "Оконщики Петрова") }

        assertFalse(DefaultSeed.isUntouchedSeed(renamed))
    }

    @Test
    fun `a deleted contractor cancels the reset`() {
        assertFalse(DefaultSeed.isUntouchedSeed(seeded().drop(1)))
    }

    @Test
    fun `an archived contractor cancels the reset`() {
        val archived = seeded().toMutableList().apply { this[3] = this[3].copy(isArchived = true) }

        assertFalse(DefaultSeed.isUntouchedSeed(archived))
    }

    /** Перестановка — тоже правка: прораб расставил подрядчиков под свою стройку. */
    @Test
    fun `a reordered pool cancels the reset`() {
        val reordered = seeded().toMutableList().apply {
            this[0] = this[0].copy(orderIndex = 1)
            this[1] = this[1].copy(orderIndex = 0)
        }

        assertFalse(DefaultSeed.isUntouchedSeed(reordered))
    }
}
