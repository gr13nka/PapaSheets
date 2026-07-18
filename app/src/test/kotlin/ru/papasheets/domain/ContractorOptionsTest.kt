package ru.papasheets.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.papasheets.data.db.entity.ContractorEntity

class ContractorOptionsTest {

    private fun contractor(id: String, archived: Boolean = false) = ContractorEntity(
        id = id, name = "Подрядчик $id", shortName = id, colorIndex = 0, orderIndex = 0,
        isArchived = archived, createdAt = 0,
    )

    @Test
    fun `create mode (no current contractor) returns only active`() {
        val all = listOf(contractor("A"), contractor("B", archived = true))

        val options = buildContractorOptions(all, currentContractorId = null)

        assertEquals(listOf("A"), options.map { it.id })
    }

    @Test
    fun `edit mode with active current contractor returns active without duplicates`() {
        val all = listOf(contractor("A"), contractor("B"))

        val options = buildContractorOptions(all, currentContractorId = "A")

        assertEquals(listOf("A", "B"), options.map { it.id })
    }

    @Test
    fun `edit mode with archived current contractor appends it after active`() {
        val all = listOf(contractor("A"), contractor("B", archived = true))

        val options = buildContractorOptions(all, currentContractorId = "B")

        assertEquals(listOf("A", "B"), options.map { it.id })
    }

    @Test
    fun `unknown current contractor id is ignored`() {
        val all = listOf(contractor("A"))

        val options = buildContractorOptions(all, currentContractorId = "missing")

        assertEquals(listOf("A"), options.map { it.id })
    }

    @Test
    fun `display name marks archived contractors`() {
        assertEquals("Подрядчик A", contractorDisplayName(contractor("A")))
        assertEquals("Подрядчик B (архив)", contractorDisplayName(contractor("B", archived = true)))
    }
}
