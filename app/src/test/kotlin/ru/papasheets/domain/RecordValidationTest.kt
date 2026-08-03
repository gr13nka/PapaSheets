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

    /**
     * Поле заархивировали уже после того, как в нём что-то написали: в форме его больше нет, а в
     * записи содержимое есть. Пустой такая запись не является, и отказать в сохранении правки
     * значило бы запереть её — разархивировать поле ради этого прораба заставлять не за что.
     */
    @Test
    fun `a record whose only value sits in an archived field is not blank`() {
        val archived = testField("f-archived", orderIndex = 2)
        // Форма показывает только активные поля, значения записи приходят все.
        val result = validateRecord(
            contractorId = "c",
            fields = listOf(location),
            values = mapOf(archived.id to "12 м²"),
            hasPhoto = false,
        )

        assertFalse(result.isBlankRecord)
        assertTrue(result.isValid)
    }

    /** Архивное значение спасает запись от «пусто», но обязательное активное поле всё равно требуется. */
    @Test
    fun `an archived value does not excuse an empty required field`() {
        val result = validateRecord("c", fields, mapOf("f-archived" to "12 м²"), hasPhoto = false)

        assertFalse(result.isBlankRecord)
        assertEquals(setOf(work.id), result.emptyRequiredFieldIds)
        assertFalse(result.isValid)
    }

    /**
     * Ради этого правило и заводилось: прораб успел ввести локацию и свернул приложение — запись
     * должна лечь в журнал недозаполненной, а не пропасть. Обязательные поля спрашивает только
     * кнопка «Сохранить».
     */
    @Test
    fun `closing saves a record with an empty required field`() {
        val validation = validateRecord("c", fields, mapOf(location.id to "1-01"), hasPhoto = false)

        assertFalse(validation.isValid)
        assertEquals(RecordCloseAction.Save, recordCloseAction(validation))
    }

    @Test
    fun `closing an untouched form saves nothing`() {
        val validation = validateRecord("c", fields, emptyMap(), hasPhoto = false)

        assertEquals(RecordCloseAction.Discard, recordCloseAction(validation))
    }

    /** Одно фото без единого заполненного поля — уже запись, её тоже сохраняем. */
    @Test
    fun `closing saves a record that has only a photo`() {
        val validation = validateRecord("c", fields, emptyMap(), hasPhoto = true)

        assertEquals(RecordCloseAction.Save, recordCloseAction(validation))
    }

    /** Подрядчик обязателен схемой БД: сохранить некуда, поэтому форма остаётся на экране. */
    @Test
    fun `closing is refused while the contractor is not picked`() {
        val validation = validateRecord(null, fields, mapOf(location.id to "1-01"), hasPhoto = false)

        assertEquals(RecordCloseAction.KeepOpen, recordCloseAction(validation))
    }

    /** Пустая форма без подрядчика — просто закрыть: держать прораба не за что, терять нечего. */
    @Test
    fun `an empty form without a contractor closes instead of complaining`() {
        val validation = validateRecord(null, fields, emptyMap(), hasPhoto = false)

        assertEquals(RecordCloseAction.Discard, recordCloseAction(validation))
    }
}
