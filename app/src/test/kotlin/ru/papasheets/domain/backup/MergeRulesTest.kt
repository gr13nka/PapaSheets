package ru.papasheets.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class MergeRulesTest {

    @Test
    fun `replaceable inserts when absent`() {
        assertEquals(MergeAction.INSERT, MergeRules.forReplaceable(alreadyExists = false))
    }

    @Test
    fun `replaceable always replaces when present`() {
        assertEquals(MergeAction.REPLACE, MergeRules.forReplaceable(alreadyExists = true))
    }

    @Test
    fun `immutable inserts when absent`() {
        assertEquals(MergeAction.INSERT, MergeRules.forImmutable(alreadyExists = false))
    }

    @Test
    fun `immutable skips when present`() {
        assertEquals(MergeAction.SKIP, MergeRules.forImmutable(alreadyExists = true))
    }

    @Test
    fun `record inserts when there is no existing row`() {
        assertEquals(MergeAction.INSERT, MergeRules.forRecord(existingUpdatedAt = null, importedUpdatedAt = 100))
    }

    @Test
    fun `record replaces when imported is newer`() {
        assertEquals(MergeAction.REPLACE, MergeRules.forRecord(existingUpdatedAt = 100, importedUpdatedAt = 200))
    }

    @Test
    fun `record skips when imported is older`() {
        assertEquals(MergeAction.SKIP, MergeRules.forRecord(existingUpdatedAt = 200, importedUpdatedAt = 100))
    }

    @Test
    fun `record skips when imported equals existing — re-import must be idempotent`() {
        assertEquals(MergeAction.SKIP, MergeRules.forRecord(existingUpdatedAt = 100, importedUpdatedAt = 100))
    }

    @Test
    fun `merge stats accumulate actions by kind`() {
        val stats = listOf(MergeAction.INSERT, MergeAction.INSERT, MergeAction.REPLACE, MergeAction.SKIP, MergeAction.SKIP, MergeAction.SKIP)
            .fold(MergeStats()) { acc, action -> acc + action }
        assertEquals(MergeStats(added = 2, updated = 1, skipped = 3), stats)
    }
}
