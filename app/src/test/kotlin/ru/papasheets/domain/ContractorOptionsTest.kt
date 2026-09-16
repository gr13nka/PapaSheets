package ru.papasheets.domain

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import ru.papasheets.data.db.entity.ContractorEntity

class ContractorOptionsTest {

    private fun contractor(id: String, archived: Boolean = false) = ContractorEntity(
        id = id, name = "Подрядчик $id", shortName = id, colorIndex = 0, orderIndex = 0,
        isArchived = archived, createdAt = 0,
     journalId = "j1",)

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
    fun `display name keeps active contractors bare and puts archived ones into the template`() {
        assertEquals("Подрядчик A", contractorDisplayName(contractor("A"), "%1\$s (архив)"))
        assertEquals("Подрядчик B (архив)", contractorDisplayName(contractor("B", archived = true), "%1\$s (архив)"))
    }

    /** Шаблон из настоящих strings.xml: тест ловит и правку функции, и поломку перевода. */
    @Test
    fun `record_contractor_archived marks archived contractors in English and Russian`() {
        val archived = contractor("B", archived = true)

        assertEquals("Подрядчик B (archived)", contractorDisplayName(archived, archivedTemplate("values")))
        assertEquals("Подрядчик B (архив)", contractorDisplayName(archived, archivedTemplate("values-ru")))
    }

    /** Рабочий каталог JVM-тестов — модуль `app`, отсюда относительный путь к ресурсам. */
    private fun archivedTemplate(valuesDir: String): String {
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$valuesDir/strings.xml"))
            .getElementsByTagName("string")
        return (0 until strings.length)
            .map { strings.item(it) as Element }
            .single { it.getAttribute("name") == "record_contractor_archived" }
            .textContent
    }
}
