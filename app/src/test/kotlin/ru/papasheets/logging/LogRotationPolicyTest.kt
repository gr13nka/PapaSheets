package ru.papasheets.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRotationPolicyTest {

    @Test
    fun `shouldRotate is false strictly below the limit`() {
        assertFalse(LogRotationPolicy.shouldRotate(LogRotationPolicy.MAX_FILE_BYTES - 1, LogRotationPolicy.MAX_FILE_BYTES))
    }

    @Test
    fun `shouldRotate is true exactly at the limit`() {
        assertTrue(LogRotationPolicy.shouldRotate(LogRotationPolicy.MAX_FILE_BYTES, LogRotationPolicy.MAX_FILE_BYTES))
    }

    @Test
    fun `shouldRotate is true above the limit`() {
        assertTrue(LogRotationPolicy.shouldRotate(LogRotationPolicy.MAX_FILE_BYTES + 1, LogRotationPolicy.MAX_FILE_BYTES))
    }

    @Test
    fun `rotatedName for index 0 is the base name unchanged`() {
        assertEquals("app.log", LogRotationPolicy.rotatedName("app.log", 0))
    }

    @Test
    fun `rotatedName for a negative index is also the base name unchanged`() {
        assertEquals("app.log", LogRotationPolicy.rotatedName("app.log", -1))
    }

    @Test
    fun `rotatedName for a positive index appends it after the base name`() {
        assertEquals("app.log.2", LogRotationPolicy.rotatedName("app.log", 2))
    }

    @Test
    fun `rotation plan for keep 1 only bumps the current file to slot 1`() {
        assertEquals(
            listOf("app.log" to "app.log.1"),
            LogRotationPolicy.rotationPlan("app.log", keep = 1),
        )
    }

    @Test
    fun `rotation plan for keep 2 shifts slot 1 up before bumping the current file`() {
        assertEquals(
            listOf("app.log.1" to "app.log.2", "app.log" to "app.log.1"),
            LogRotationPolicy.rotationPlan("app.log", keep = 2),
        )
    }

    @Test
    fun `rotation plan for keep 3 shifts older slots first, highest index first`() {
        assertEquals(
            listOf(
                "app.log.2" to "app.log.3",
                "app.log.1" to "app.log.2",
                "app.log" to "app.log.1",
            ),
            LogRotationPolicy.rotationPlan("app.log", keep = 3),
        )
    }
}
