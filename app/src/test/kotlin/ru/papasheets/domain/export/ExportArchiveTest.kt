package ru.papasheets.domain.export

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportArchiveTest {

    private val at = LocalDateTime.of(2026, 7, 20, 14, 30, 5)

    @Test
    fun `archive name embeds a chronologically sortable stamp before the extension`() {
        assertEquals("Июль 2026 (2026-07-20 14-30-05).xlsx", ExportArchive.archiveName("Июль 2026.xlsx", at))
    }

    @Test
    fun `archive name keeps names without extension whole`() {
        assertEquals("бэкап (2026-07-20 14-30-05)", ExportArchive.archiveName("бэкап", at))
    }

    @Test
    fun `nothing is obsolete while at or below the keep limit`() {
        val names = listOf(
            "Июль 2026 (2026-07-18 09-00-00).xlsx",
            "Июль 2026 (2026-07-19 09-00-00).xlsx",
            "Июль 2026 (2026-07-20 09-00-00).xlsx",
        )
        assertEquals(emptyList<String>(), ExportArchive.obsolete("Июль 2026.xlsx", names))
    }

    @Test
    fun `the oldest beyond the keep limit are obsolete`() {
        val names = listOf(
            "Июль 2026 (2026-07-17 09-00-00).xlsx",
            "Июль 2026 (2026-07-20 09-00-00).xlsx", // новейший — вразнобой, порядок восстанавливает сортировка
            "Июль 2026 (2026-07-18 09-00-00).xlsx",
            "Июль 2026 (2026-07-19 09-00-00).xlsx",
            "Июль 2026 (2026-07-16 09-00-00).xlsx",
        )
        // Оставляем 3 новейших (18, 19, 20) → лишние 16 и 17.
        assertEquals(
            listOf(
                "Июль 2026 (2026-07-16 09-00-00).xlsx",
                "Июль 2026 (2026-07-17 09-00-00).xlsx",
            ),
            ExportArchive.obsolete("Июль 2026.xlsx", names),
        )
    }

    @Test
    fun `archives of other files in the same folder are never touched`() {
        val names = listOf(
            "Июль 2026 (2026-07-16 09-00-00).xlsx",
            "Июль 2026 (2026-07-17 09-00-00).xlsx",
            "Июль 2026 (2026-07-18 09-00-00).xlsx",
            "Июль 2026 (2026-07-19 09-00-00).xlsx",
            "Август 2026 (2026-07-19 09-00-00).xlsx", // другой месяц — вне ротации Июля
            "Июль 2026.csv", // другой формат того же месяца — другое расширение, вне ротации xlsx
        )
        assertEquals(
            listOf("Июль 2026 (2026-07-16 09-00-00).xlsx"),
            ExportArchive.obsolete("Июль 2026.xlsx", names),
        )
    }
}
