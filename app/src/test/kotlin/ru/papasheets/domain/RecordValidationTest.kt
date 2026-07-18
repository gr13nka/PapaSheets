package ru.papasheets.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.papasheets.testing.testField

class RecordValidationTest {

    private val location = testField("location")
    private val work = testField("work", isRequired = true, orderIndex = 1)
    private val fields = listOf(location, work)

    @Test
    fun `required field left empty is reported`() {
        val result = validateRecord("c", fields, mapOf(location.id to "1-01"), hasPhoto = false)

        assertFalse(result.isValid)
        assertEquals(setOf(work.id), result.emptyRequiredFieldIds)
        assertFalse(result.isBlankRecord)
    }

    @Test
    fun `record without a photo is valid`() {
        // Прямое требование: фото больше не обязательно.
        val result = validateRecord("c", fields, mapOf(work.id to "штукатурка"), hasPhoto = false)

        assertTrue(result.isValid)
    }

    @Test
    fun `completely empty record is rejected`() {
        // Иначе промах мимо ячейки матрицы молча плодил бы пустые строки в журнале.
        val result = validateRecord("c", fields, emptyMap(), hasPhoto = false)

        assertFalse(result.isValid)
        assertTrue(result.isBlankRecord)
        // Пустота — самостоятельная жалоба, обязательные поля поверх неё не перечисляются.
        assertEquals(emptySet<String>(), result.emptyRequiredFieldIds)
    }

    @Test
    fun `record with a photo and no values is valid when nothing is required`() {
        val result = validateRecord("c", listOf(location), emptyMap(), hasPhoto = true)

        assertTrue(result.isValid)
    }

    @Test
    fun `whitespace-only values count as empty`() {
        val result = validateRecord("c", fields, mapOf(location.id to "   ", work.id to "\n "), hasPhoto = false)

        assertTrue(result.isBlankRecord)
        assertFalse(result.isValid)
    }

    @Test
    fun `missing contractor is reported alongside a filled record`() {
        val result = validateRecord(null, fields, mapOf(work.id to "штукатурка"), hasPhoto = false)

        assertFalse(result.isValid)
        assertTrue(result.contractorMissing)
    }

    @Test
    fun `journal without required fields accepts a single filled field`() {
        val result = validateRecord("c", listOf(location), mapOf(location.id to "1-01"), hasPhoto = false)

        assertTrue(result.isValid)
    }
}
