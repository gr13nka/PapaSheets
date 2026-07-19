package ru.papasheets.data.repo

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.papasheets.data.db.dao.FieldDefDao
import ru.papasheets.data.db.entity.FieldDefEntity

/**
 * Всё, что экран полей может изменить в определении: `id`, `orderIndex`, `isBuiltIn` и `createdAt`
 * сюда не входят — ими распоряжается [FieldRepository], и снаружи их не назначают.
 */
data class FieldDraft(
    /** Подпись подколонки в шапке матрицы — там тесно, поэтому коротко: «Л», «ОБЪЁМ». */
    val title: String,
    /** Полное имя строки в форме записи: «Локация», «Объём бетона». */
    val label: String,
    val columnWidthDp: Int,
    /** Потолок строк текста в ячейке; 0 = без ограничения. */
    val maxLines: Int,
    val isRequired: Boolean,
    val suggestFromHistory: Boolean,
    /**
     * Показывать ли колонку в сжатом ярусе («картина месяца»). Ограничения на число таких полей нет,
     * но ширины там хватает на одну-две колонки: третья не столько добавит информации, сколько
     * отнимет читаемость у первых двух.
     */
    val showAtCompactLod: Boolean,
)

/**
 * Исход попытки удалить поле. Удаление — не общий случай, а исключение: колонку, в которой уже
 * что-то написано, прораб архивирует, и данные остаются на месте.
 */
sealed interface FieldDeleteOutcome {
    /** Поле удалено вместе со своими пресетами (`field_presets` уходят по `ON DELETE CASCADE`). */
    data object Deleted : FieldDeleteOutcome

    /** Встроенное поле удалить нельзя: сид и бэкап с другого устройства всё равно вернут его обратно. */
    data object BuiltIn : FieldDeleteOutcome

    /** Поле заполнено в [valueCount] записях — удалить его значило бы стереть их содержимое. */
    data class InUse(val valueCount: Int) : FieldDeleteOutcome
}

/**
 * Определения полей записи. Активные поля — это одновременно подколонки матрицы, колонки экспорта и
 * строки формы записи: набор и его порядок у всех трёх один, поэтому и источник должен быть один,
 * иначе экспорт разъедется с матрицей по составу колонок.
 *
 * Здесь же живут правила, а не на экране полей: генерация `id`/`orderIndex`, запрет удаления
 * встроенного поля и заполненного поля. Экран показывает исход ([FieldDeleteOutcome]), но не решает,
 * каким он будет.
 */
class FieldRepository(private val dao: FieldDefDao) {
    /**
     * Поля, которыми пользуются сейчас, по `orderIndex`. Архивное поле сохраняет значения в старых
     * записях, но больше не показывается и не предлагается к заполнению.
     */
    fun observeActive(): Flow<List<FieldDefEntity>> = dao.observeAll().map { fields -> fields.filter { !it.isArchived } }

    /** Все определения, включая архивные — их нужно видеть экрану полей и бэкапу. */
    fun observeAll(): Flow<List<FieldDefEntity>> = dao.observeAll()

    /** Все определения, включая архивные — их нужно видеть экрану полей и бэкапу. */
    suspend fun getAll(): List<FieldDefEntity> = dao.getAll()

    /** Новое поле в конец списка колонок. */
    suspend fun create(draft: FieldDraft) {
        val existing = dao.getAll()
        dao.insert(
            FieldDefEntity(
                id = UUID.randomUUID().toString(),
                title = draft.title.trim(),
                label = draft.label.trim(),
                orderIndex = (existing.maxOfOrNull { it.orderIndex } ?: -1) + 1,
                isArchived = false,
                isBuiltIn = false,
                isRequired = draft.isRequired,
                suggestFromHistory = draft.suggestFromHistory,
                columnWidthDp = draft.columnWidthDp,
                maxLines = draft.maxLines,
                showAtCompactLod = draft.showAtCompactLod,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Правка определения: меняется только то, что прораб видит на экране полей. */
    suspend fun update(field: FieldDefEntity, draft: FieldDraft) = dao.update(
        field.copy(
            title = draft.title.trim(),
            label = draft.label.trim(),
            columnWidthDp = draft.columnWidthDp,
            maxLines = draft.maxLines,
            isRequired = draft.isRequired,
            suggestFromHistory = draft.suggestFromHistory,
            showAtCompactLod = draft.showAtCompactLod,
        ),
    )

    /** Архив вместо удаления: значения архивного поля остаются в записях, просто перестают показываться. */
    suspend fun setArchived(field: FieldDefEntity, archived: Boolean) = dao.update(field.copy(isArchived = archived))

    /** Персистит новый порядок drag-reorder'а: orderIndex = позиция в списке. */
    suspend fun reorder(ordered: List<FieldDefEntity>) =
        dao.updateAll(ordered.mapIndexed { index, field -> field.copy(orderIndex = index) })

    /**
     * Удаляет поле, если это вообще допустимо. Проверка идёт здесь, а не в UI: `record_values`
     * ссылается на `field_defs` с `ON DELETE RESTRICT`, так что запрещённое удаление всё равно
     * упало бы — но исключением SQLite посреди экрана, а не внятным ответом.
     */
    suspend fun delete(field: FieldDefEntity): FieldDeleteOutcome {
        if (field.isBuiltIn) return FieldDeleteOutcome.BuiltIn
        val valueCount = dao.valueCount(field.id)
        if (valueCount > 0) return FieldDeleteOutcome.InUse(valueCount)
        dao.delete(field)
        return FieldDeleteOutcome.Deleted
    }

    /** Восстанавливает определение поля из бэкапа как есть (решение принимает [ru.papasheets.domain.backup.MergeRules]). */
    suspend fun upsertFromBackup(field: FieldDefEntity) = dao.upsertFromBackup(field)

}
