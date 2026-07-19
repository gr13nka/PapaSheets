package ru.papasheets.testing

import ru.papasheets.data.db.entity.FieldDefEntity
import ru.papasheets.data.db.entity.RecordEntity
import ru.papasheets.data.db.entity.RecordValueEntity
import ru.papasheets.data.db.entity.RecordWithValues
import ru.papasheets.exportkit.backup.BuiltInFields

/** Определение поля с умолчаниями — в тесте задаётся только то, что тест проверяет. */
fun testField(
    id: String,
    title: String = id,
    label: String = id,
    orderIndex: Int = 0,
    isRequired: Boolean = false,
    suggestFromHistory: Boolean = false,
    columnWidthDp: Int = 56,
    maxLines: Int = 1,
    showAtCompactLod: Boolean = true,
): FieldDefEntity = FieldDefEntity(
    id = id,
    title = title,
    label = label,
    orderIndex = orderIndex,
    isArchived = false,
    isBuiltIn = false,
    isRequired = isRequired,
    suggestFromHistory = suggestFromHistory,
    columnWidthDp = columnWidthDp,
    maxLines = maxLines,
    showAtCompactLod = showAtCompactLod,
    createdAt = 0,
)

/**
 * Встроенные поля ровно такими, какими их заводит в БД `BuiltInFieldSeed` — на них проверяются
 * раскладка матрицы и геометрия экспорта, поэтому подменять их упрощёнными нельзя.
 */
val builtInFields: List<FieldDefEntity> = BuiltInFields.ALL.map { spec ->
    FieldDefEntity(
        id = spec.id,
        title = spec.title,
        label = spec.label,
        orderIndex = spec.orderIndex,
        isArchived = false,
        isBuiltIn = true,
        isRequired = spec.isRequired,
        suggestFromHistory = spec.suggestFromHistory,
        columnWidthDp = spec.columnWidthDp,
        maxLines = spec.maxLines,
        showAtCompactLod = spec.showAtCompactLod,
        createdAt = 0,
    )
}

/** Запись со значениями; пустые значения не передаются — их и в БД не бывает (см. RecordValueEntity). */
fun testRecord(
    id: String,
    journalId: String = "j",
    dateEpochDay: Long = 0,
    contractorId: String = "c",
    photoId: String? = null,
    createdAt: Long = 0,
    values: Map<String, String> = emptyMap(),
): RecordWithValues = RecordWithValues(
    record = RecordEntity(
        id = id,
        journalId = journalId,
        dateEpochDay = dateEpochDay,
        contractorId = contractorId,
        photoId = photoId,
        createdAt = createdAt,
        updatedAt = createdAt,
    ),
    values = values.filterValues { it.isNotBlank() }.map { (fieldId, value) -> RecordValueEntity(id, fieldId, value) },
)
