package ru.papasheets.localization

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun `empty tag follows the system`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTag(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTag(""))
    }

    @Test
    fun `supported regional tags map by language`() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguageTag("en-US"))
        assertEquals(AppLanguage.RUSSIAN, AppLanguage.fromLanguageTag("ru-RU"))
    }

    @Test
    fun `unsupported tag falls back to system`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTag("de-DE"))
    }
}
