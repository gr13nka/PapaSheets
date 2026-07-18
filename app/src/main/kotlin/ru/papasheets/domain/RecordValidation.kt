package ru.papasheets.domain

import ru.papasheets.data.db.entity.FieldDefEntity

/** Что мешает сохранить форму записи; [isValid] — можно писать в БД. */
data class RecordValidation(
    val contractorMissing: Boolean,
    /** Поля с `isRequired`, оставшиеся пустыми — форма подсвечивает именно их. */
    val emptyRequiredFieldIds: Set<String>,
    /** Ни одного заполненного поля и нет фото. */
    val isBlankRecord: Boolean,
) {
    val isValid: Boolean get() = !contractorMissing && emptyRequiredFieldIds.isEmpty() && !isBlankRecord
}

/**
 * Единственное место, где решается, что считать заполненной записью.
 *
 * Фото необязательно, а вот совсем пустая запись — нет: без этого правила промах пальцем мимо ячейки
 * матрицы молча плодил бы пустые строки в журнале, причём обязательных полей может не быть заведено
 * ни одного. Пустота — отдельная, самостоятельная жалоба: перечислять поверх неё незаполненные
 * обязательные поля незачем, пусто и так всё.
 *
 * Заполненность значения понимается так же, как при хранении ([ru.papasheets.data.repo.RecordRepository]):
 * пробелы значением не являются.
 */
fun validateRecord(
    contractorId: String?,
    fields: List<FieldDefEntity>,
    values: Map<String, String>,
    hasPhoto: Boolean,
): RecordValidation {
    val filledFieldIds = fields.filter { values[it.id]?.isNotBlank() == true }.mapTo(HashSet()) { it.id }
    if (filledFieldIds.isEmpty() && !hasPhoto) {
        return RecordValidation(contractorId == null, emptySet(), isBlankRecord = true)
    }
    return RecordValidation(
        contractorMissing = contractorId == null,
        emptyRequiredFieldIds = fields.filter { it.isRequired && it.id !in filledFieldIds }.mapTo(HashSet()) { it.id },
        isBlankRecord = false,
    )
}
