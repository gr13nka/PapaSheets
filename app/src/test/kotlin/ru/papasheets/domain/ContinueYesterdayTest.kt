package ru.papasheets.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.papasheets.exportkit.backup.BuiltInFields
import ru.papasheets.testing.builtInFields
import ru.papasheets.testing.testRecord

class ContinueYesterdayTest {

    private fun record(location: String, work: String) = testRecord(
        id = "r",
        values = mapOf(BuiltInFields.LOCATION_ID to location, BuiltInFields.WORK_ID to work),
    )

    @Test
    fun `yesterday is one day before the form date`() {
        assertEquals(99L, ContinueYesterday.yesterday(100L))
    }

    @Test
    fun `single candidate is returned for auto-apply`() {
        val record = record("1-01", "работа")
        assertEquals(record, ContinueYesterday.singleCandidate(listOf(record)))
    }

    @Test
    fun `no candidate when there are no yesterday records`() {
        assertNull(ContinueYesterday.singleCandidate(emptyList()))
    }

    @Test
    fun `no single candidate when there are several yesterday records`() {
        val records = listOf(record("1-01", "a"), record("1-02", "b"))
        assertNull(ContinueYesterday.singleCandidate(records))
    }

    @Test
    fun `preview combines the label and the truncated content`() {
        val record = record("2-14", "короткий текст")
        assertEquals("2-14 — короткий текст", ContinueYesterday.preview(record, builtInFields))
    }

    @Test
    fun `preview omits the label when it is blank`() {
        val record = record("", "текст без локации")
        assertEquals("текст без локации", ContinueYesterday.preview(record, builtInFields))
    }

    @Test
    fun `preview truncates long content to 60 characters with ellipsis`() {
        val longText = "a".repeat(80)
        val record = record("1-01", longText)
        val result = ContinueYesterday.preview(record, builtInFields)
        assertEquals("1-01 — " + "a".repeat(60) + "…", result)
    }
}
