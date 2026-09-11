package ru.papasheets.localization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirstLaunchLanguageTest {
    @Test
    fun `updated install without a chosen language stays Russian`() {
        assertEquals(
            AppLanguage.RUSSIAN,
            firstLaunchLanguage(existingInstall = true, decided = false, chosen = AppLanguage.SYSTEM),
        )
    }

    @Test
    fun `new install keeps following the system`() {
        assertNull(firstLaunchLanguage(existingInstall = false, decided = false, chosen = AppLanguage.SYSTEM))
    }

    @Test
    fun `a later return to the system language is not overridden once decided`() {
        assertNull(firstLaunchLanguage(existingInstall = true, decided = true, chosen = AppLanguage.SYSTEM))
    }

    @Test
    fun `an explicitly chosen language is left alone`() {
        assertNull(firstLaunchLanguage(existingInstall = true, decided = false, chosen = AppLanguage.ENGLISH))
    }
}
