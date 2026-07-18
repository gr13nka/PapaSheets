package ru.papasheets.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.papasheets.data.db.entity.RecordEntity

class ContinueYesterdayTest {

    private fun record(locationCode: String, workText: String) = RecordEntity(
        id = "r", journalId = "j", dateEpochDay = 0, contractorId = "c",
        locationCode = locationCode, workText = workText, photoId = null, createdAt = 0, updatedAt = 0,
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
    fun `preview combines location and truncated work text`() {
        val record = record("2-14", "короткий текст")
        assertEquals("2-14 — короткий текст", ContinueYesterday.preview(record))
    }

    @Test
    fun `preview omits location when blank`() {
        val record = record("", "текст без локации")
        assertEquals("текст без локации", ContinueYesterday.preview(record))
    }

    @Test
    fun `preview truncates long work text to 60 characters with ellipsis`() {
        val longText = "a".repeat(80)
        val record = record("1-01", longText)
        val result = ContinueYesterday.preview(record)
        assertEquals("1-01 — " + "a".repeat(60) + "…", result)
    }
}
