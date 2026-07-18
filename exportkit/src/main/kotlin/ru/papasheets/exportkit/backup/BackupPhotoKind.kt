package ru.papasheets.exportkit.backup

/** Различает две сжатые копии одного фото внутри архива — обе лежат под своим префиксом `photos/<kind>/`. */
enum class BackupPhotoKind(val zipDir: String) {
    MEDIUM("medium"),
    THUMB("thumb"),
}
