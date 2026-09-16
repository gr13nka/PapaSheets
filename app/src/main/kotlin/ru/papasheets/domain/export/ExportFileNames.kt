package ru.papasheets.domain.export

/** Separate tables may have identical titles; an existing file is never implicitly theirs. */
internal object ExportFileNames {
    fun available(suggested: String, existing: Set<String>): String {
        val clean = suggested.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifBlank { "Report.xlsx" }
        val stem = clean.substringBeforeLast('.').take(120)
        val extension = clean.substringAfterLast('.', "xlsx")
        val occupied = existing.map { it.lowercase(java.util.Locale.ROOT) }.toSet()
        var candidate = "$stem.$extension"
        var suffix = 2
        while (candidate.lowercase(java.util.Locale.ROOT) in occupied) candidate = "$stem (${suffix++}).$extension"
        return candidate
    }
}
