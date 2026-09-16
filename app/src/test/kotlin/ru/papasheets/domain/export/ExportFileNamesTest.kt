package ru.papasheets.domain.export

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportFileNamesTest {
    @Test fun repeatedTitlesNeverClaimAnExistingReport() {
        assertEquals("Site (3).xlsx", ExportFileNames.available("Site.xlsx", setOf("Site.xlsx", "site (2).xlsx")))
        assertEquals("Site.csv", ExportFileNames.available("Site.csv", setOf("Site.xlsx")))
    }

    @Test fun namesCannotEscapeTheSelectedDirectory() {
        assertEquals("A_B_C.xlsx", ExportFileNames.available("A/B\\C.xlsx", emptySet()))
    }
}
