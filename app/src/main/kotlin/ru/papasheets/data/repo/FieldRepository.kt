package ru.papasheets.data.repo

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.papasheets.data.db.dao.FieldDefDao
import ru.papasheets.data.db.entity.FieldDefEntity

/**
 * Всё, что экран настройки группы может изменить в определении: `id`, `orderIndex`, `isBuiltIn` и
 * `createdAt` сюда не входят — ими распоряжается [FieldRepository], и снаружи их не назначают.
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

/** Определение поля как черновик — исходная точка правки в [FieldRepository.edit]. */
fun FieldDefEntity.toDraft(): FieldDraft = FieldDraft(
    title = title,
    label = label,
    columnWidthDp = columnWidthDp,
    maxLines = maxLines,
    isRequired = isRequired,
    suggestFromHistory = suggestFromHistory,
    showAtCompactLod = showAtCompactLod,
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
 * Здесь же живут правила, а не на экране настройки группы: генерация `id`/`orderIndex`, запрет
 * удаления встроенного поля и заполненного поля. Экран показывает исход ([FieldDeleteOutcome]), но
 * не решает, каким он будет.
 *
 * Экран настройки группы сохраняет каждую правку немедленно — заголовок, ширина, каждый флаг пишутся
 * по отдельности, а не одним «Сохранить» в конце. Поэтому соседние правки одного поля успевают
 * прийти сюда быстрее, чем экран получит назад обновлённый список: черновик, собранный из ТАКОЙ
 * устаревшей копии, переписал бы строку целиком и стёр то, что только что записала предыдущая
 * правка. Все операции ниже поэтому берут строку заново из [dao] в момент выполнения (а не то, что
 * передал вызывающий) под одним [lock] — FIFO-порядок `Mutex` заодно гарантирует, что правки одного
 * поля применяются в том же порядке, в котором их отправил пользователь.
 */
class FieldRepository(private val dao: FieldDefDao) {
    private val lock = Mutex()

    fun observeForJournal(journalId: String): Flow<List<FieldDefEntity>> = dao.observeForJournal(journalId)
    fun observeActive(journalId: String): Flow<List<FieldDefEntity>> = observeForJournal(journalId).map { all -> all.filter { !it.isArchived } }
    suspend fun getForJournal(journalId: String) = dao.getForJournal(journalId)

    /**
     * Поля, которыми пользуются сейчас, по `orderIndex`. Архивное поле сохраняет значения в старых
     * записях, но больше не показывается и не предлагается к заполнению.
     */

    /** Все определения, включая архивные — их нужно видеть экрану настройки группы и бэкапу. */

    /** Все определения, включая архивные — их нужно видеть экрану настройки группы и бэкапу. */
    suspend fun getAll(): List<FieldDefEntity> = dao.getAll()

    /** Новое поле в конец списка колонок. @return id заведённого поля — экран сразу открывает его редактор. */
    suspend fun create(journalId: String, draft: FieldDraft): String = lock.withLock {
        val existing = dao.getForJournal(journalId)
        val id = UUID.randomUUID().toString()
        dao.insert(
            FieldDefEntity(
                id = id,
                journalId = journalId,
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
        id
    }

    /**
     * Правка определения: [change] получает поле таким, какое оно СЕЙЧАС в БД, а не то, что показывал
     * экран в момент тапа — иначе набранный и уже отправленный заголовок мог бы пропасть под правкой
     * ширины, отправленной секундой позже с устаревшей копией текста. Поле, исчезнувшее параллельно
     * (удалено с другого места), тихо пропускается: редактировать больше нечего.
     */
    suspend fun edit(fieldId: String, change: (FieldDraft) -> FieldDraft) = lock.withLock {
        val current = dao.getAll().find { it.id == fieldId } ?: return@withLock
        val draft = change(current.toDraft())
        dao.update(
            current.copy(
                title = draft.title.trim(),
                label = draft.label.trim(),
                columnWidthDp = draft.columnWidthDp,
                maxLines = draft.maxLines,
                isRequired = draft.isRequired,
                suggestFromHistory = draft.suggestFromHistory,
                showAtCompactLod = draft.showAtCompactLod,
            ),
        )
    }

    /** Архив вместо удаления: значения архивного поля остаются в записях, просто перестают показываться. */
    suspend fun setArchived(fieldId: String, archived: Boolean) = lock.withLock {
        val current = dao.getAll().find { it.id == fieldId } ?: return@withLock
        dao.update(current.copy(isArchived = archived))
    }

    /** Персистит новый порядок drag-reorder'а: orderIndex = позиция в списке; неизвестные id пропускаются. */
    suspend fun reorder(orderedIds: List<String>) = lock.withLock {
        val byId = dao.getAll().associateBy { it.id }
        dao.updateAll(orderedIds.mapIndexedNotNull { index, id -> byId[id]?.copy(orderIndex = index) })
    }

    /**
     * Удаляет поле, если это вообще допустимо. Проверка идёт здесь, а не в UI: `record_values`
     * ссылается на `field_defs` с `ON DELETE RESTRICT`, так что запрещённое удаление всё равно
     * упало бы — но исключением SQLite посреди экрана, а не внятным ответом.
     */
    suspend fun delete(fieldId: String): FieldDeleteOutcome = lock.withLock {
        val field = dao.getAll().find { it.id == fieldId } ?: return@withLock FieldDeleteOutcome.Deleted
        val valueCount = dao.valueCount(field.id)
        if (valueCount > 0) return@withLock FieldDeleteOutcome.InUse(valueCount)
        dao.delete(field)
        FieldDeleteOutcome.Deleted
    }

    /** Восстанавливает определение поля из бэкапа как есть (решение принимает [ru.papasheets.domain.backup.MergeRules]). */
    suspend fun upsertFromBackup(field: FieldDefEntity) = dao.upsertFromBackup(field)
}
