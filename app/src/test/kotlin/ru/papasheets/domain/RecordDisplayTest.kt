package ru.papasheets.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.papasheets.testing.testField
import ru.papasheets.testing.testRecord

class RecordDisplayTest {

    private val location = testField("location")
    private val work = testField("work", orderIndex = 1)
    private val note = testField("note", orderIndex = 2)

    @Test
    fun `first field is the label and the rest is the content`() {
        val record = testRecord("r", values = mapOf(location.id to "1-01", work.id to "штукатурка", note.id to "переделать"))

        val display = RecordDisplay.of(record, listOf(location, work, note))

        assertEquals("1-01", display.primary)
        assertEquals(listOf("штукатурка", "переделать"), display.secondaryLines)
    }

    @Test
    fun `empty first field leaves the label blank without promoting the next value`() {
        // Иначе чип в списке дня внезапно наполнился бы текстом работ вместо кода локации.
        val record = testRecord("r", values = mapOf(work.id to "штукатурка"))

        val display = RecordDisplay.of(record, listOf(location, work))

        assertEquals("", display.primary)
        assertEquals(listOf("штукатурка"), display.secondaryLines)
    }

    @Test
    fun `unfilled fields do not leave blank lines in the content`() {
        val record = testRecord("r", values = mapOf(location.id to "1-01", note.id to "переделать"))

        val display = RecordDisplay.of(record, listOf(location, work, note))

        assertEquals(listOf("переделать"), display.secondaryLines)
    }

    @Test
    fun `journal with a single field has a label and no content`() {
        val record = testRecord("r", values = mapOf(location.id to "1-01"))

        val display = RecordDisplay.of(record, listOf(location))

        assertEquals("1-01", display.primary)
        assertEquals(emptyList<String>(), display.secondaryLines)
    }

    @Test
    fun `journal without fields yields an empty display`() {
        val display = RecordDisplay.of(testRecord("r"), emptyList())

        assertEquals("", display.primary)
        assertEquals(emptyList<String>(), display.secondaryLines)
    }
}
