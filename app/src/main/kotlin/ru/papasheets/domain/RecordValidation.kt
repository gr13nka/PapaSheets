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

/** Что делает форма записи, когда её убирают с экрана без явного «Сохранить». */
enum class RecordCloseAction {
    /** Записать как есть. Дальше форма сворачивается в полоску — запись уже в БД. */
    Save,

    /** Записывать нечего — закрыться молча, сворачивать тоже нечего. */
    Discard,

    /** Уйти нельзя, форма остаётся на экране с жалобой. */
    KeepOpen,
}

/**
 * Уход с формы — это сохранение: начатая запись не мусор, и терять набранное из-за свёрнутого
 * приложения прораб не должен. Поэтому обязательные поля здесь не требуются — их требует только
 * явное «Сохранить» ([RecordValidation.emptyRequiredFieldIds]), где напоминание уместно и не стоит
 * данных.
 *
 * Правило одно на все выходы из формы: свайп вниз, «Назад», тап по затемнению и шеврон «свернуть»
 * сначала сохраняют, а уже потом решают, показывать ли полоску свёрнутой записи. Что именно
 * происходит после [Save] — сворачивание или закрытие, — дело вызывающего; здесь только «можно ли
 * писать», и второго места с этим знанием быть не должно.
 *
 * Два исключения. Пустую форму записывать нечего — иначе промах по ячейке матрицы плодил бы пустые
 * строки (то же правило, что и у [validateRecord]), и сворачивать в полоску тоже было бы нечего.
 * А без подрядчика записи негде лежать: столбец обязателен схемой, внешним ключом на `contractors`,
 * — тут форма остаётся открытой, и уйти можно только явным отказом.
 */
fun recordCloseAction(validation: RecordValidation): RecordCloseAction = when {
    validation.isBlankRecord -> RecordCloseAction.Discard
    validation.contractorMissing -> RecordCloseAction.KeepOpen
    else -> RecordCloseAction.Save
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
 *
 * «Пусто» решается по [values] целиком, а не по одним лишь [fields]. У существующей записи значения
 * могут остаться в поле, которое с тех пор заархивировали: в форме такого поля уже нет, но в записи
 * содержимое есть. Считая её пустой, форма отказалась бы сохранить правку записи, которая на самом
 * деле не пуста, — и починить это прораб не смог бы ничем, кроме разархивирования поля.
 */
fun validateRecord(
    contractorId: String?,
    fields: List<FieldDefEntity>,
    values: Map<String, String>,
    hasPhoto: Boolean,
): RecordValidation {
    val filledFieldIds = fields.filter { values[it.id]?.isNotBlank() == true }.mapTo(HashSet()) { it.id }
    if (values.values.none { it.isNotBlank() } && !hasPhoto) {
        return RecordValidation(contractorId == null, emptySet(), isBlankRecord = true)
    }
    return RecordValidation(
        contractorMissing = contractorId == null,
        emptyRequiredFieldIds = fields.filter { it.isRequired && it.id !in filledFieldIds }.mapTo(HashSet()) { it.id },
        isBlankRecord = false,
    )
}
