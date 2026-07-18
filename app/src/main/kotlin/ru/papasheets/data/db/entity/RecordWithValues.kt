package ru.papasheets.data.db.entity

import androidx.room.Embedded
import androidx.room.Relation

/**
 * Запись вместе со всеми её значениями — единица чтения записи во всём приложении.
 *
 * Room отдаёт её одним `@Transaction`-запросом, а не склейкой двух независимых потоков: между
 * инвалидациями двух Flow существует кадр «запись уже есть, значений ещё нет», и матрица моргала бы
 * пустой ячейкой на каждом сохранении.
 *
 * Для полного бэкапа (все журналы разом) это не годится — там выборка упрётся в лимит переменных
 * SQLite, и значения читаются отдельной таблицей целиком.
 */
data class RecordWithValues(
    @Embedded val record: RecordEntity,
    @Relation(parentColumn = "id", entityColumn = "recordId")
    val values: List<RecordValueEntity>,
) {
    /** Значение поля; `""`, если строки нет — «пусто» хранится отсутствием (см. [RecordValueEntity]). */
    fun valueOf(fieldId: String): String = values.firstOrNull { it.fieldId == fieldId }?.value ?: ""

    /** Значения как `fieldId → значение`: форма записи правит и сохраняет именно такую карту. */
    fun asMap(): Map<String, String> = values.associate { it.fieldId to it.value }
}
