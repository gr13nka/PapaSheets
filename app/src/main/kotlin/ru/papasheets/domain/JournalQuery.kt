package ru.papasheets.domain

import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.data.db.entity.RecordWithValues

/**
 * Что именно показывает экран журнала: какие записи ([filter]), в каком порядке ([sort]) и каким
 * видом ([viewMode]). Одно значение на весь экран — матрица и список читают его вместе, поэтому
 * переключение вида не теряет ни фильтр, ни сортировку, а «выбранное» нельзя рассогласовать между
 * двумя видами: рассогласовывать нечего.
 */
data class JournalQuery(
    val filter: JournalFilter = JournalFilter(),
    val sort: RecordSort = RecordSort(),
    val viewMode: ViewMode = ViewMode.MATRIX,
)

/** Как журнал показан: матрица «дата × подрядчик» или плоский список записей. */
enum class ViewMode { MATRIX, LIST }

/**
 * Отбор записей журнала. Пустое условие означает «любое», а не «ничего»: фильтр по умолчанию
 * пропускает всё, и каждое добавленное условие только сужает выборку.
 */
data class JournalFilter(
    /** Пусто — все подрядчики. */
    val contractorIds: Set<String> = emptySet(),
    /** `fieldId` → допустимые значения; пустое множество для поля = «любое». */
    val values: Map<String, Set<String>> = emptyMap(),
    /** Подстрока по всем полям сразу, регистронезависимо. */
    val query: String = "",
) {
    /**
     * Сколько условий задано — для метки «Фильтр (N)». Считается здесь, а не в UI: «активен ли
     * фильтр» и «что именно фильтр делает» обязаны отвечать про одно и то же, иначе экран покажет
     * «фильтр выключен» на выборке, из которой уже что-то выпало.
     */
    val conditionCount: Int
        get() = (if (contractorIds.isEmpty()) 0 else 1) +
            values.count { it.value.isNotEmpty() } +
            (if (query.isBlank()) 0 else 1)

    val isEmpty: Boolean get() = conditionCount == 0
}

/** По какому столбцу сортировать плоский список. */
sealed interface SortKey {
    data object Date : SortKey
    data object Contractor : SortKey
    data class Field(val fieldId: String) : SortKey
}

/** Дефолт — по дате, старые сверху: так читается бумажный журнал (см. spec, M5). */
data class RecordSort(val key: SortKey = SortKey.Date, val desc: Boolean = false) {
    /**
     * Направление дат для матрицы. Матрица сортирована по дате по своей природе — строки в ней это
     * дни, — поэтому чужой ключ сортировки для неё смысла не имеет и читается как «по возрастанию».
     * Кнопка ↑↓ в матрице возвращает ключ к [SortKey.Date] (см. `JournalViewModel.toggleDateOrder`),
     * так что тумблер и здесь остаётся тумблером.
     */
    val matrixDatesDesc: Boolean get() = key is SortKey.Date && desc
}

/**
 * Отбор записей по [filter]. Применяется ОДИН раз, до раскладки, и его результат кормит оба вида —
 * матрицу и список. Второй фильтрации в проекте быть не должно: два пути неминуемо разъедутся, а
 * заметит это прораб, у которого «в матрице запись есть, а в списке нет».
 *
 * Чистая функция без Android — покрыта JVM-тестом.
 */
fun applyFilter(records: List<RecordWithValues>, filter: JournalFilter): List<RecordWithValues> {
    if (filter.isEmpty) return records
    val needle = filter.query.trim()
    val valueConditions = filter.values.filterValues { it.isNotEmpty() }
    return records.filter { record ->
        (filter.contractorIds.isEmpty() || record.record.contractorId in filter.contractorIds) &&
            // Незаполненное поле даёт "" и не попадает ни в одно множество допустимых значений
            // (пустых значений в БД не бывает) — запись без локации фильтр по локации отбрасывает.
            valueConditions.all { (fieldId, allowed) -> record.valueOf(fieldId) in allowed } &&
            (needle.isEmpty() || record.values.any { it.value.contains(needle, ignoreCase = true) })
    }
}

/**
 * Порядок записей для вида «Список». Сортировка стабильная и полностью детерминированная: при
 * равном основном ключе записи упорядочены по дате, затем по `createdAt`, — иначе строки прыгали бы
 * местами между перерисовками, а список, в котором нельзя запомнить «третья сверху», бесполезен.
 *
 * Вторичный ключ не разворачивается вместе с основным: внутри одного подрядчика или одной локации
 * записи всегда читаются хронологически, чего бы ни требовал основной столбец.
 *
 * [contractors] нужны только для подписи подрядчика; неизвестный id даёт пустой ключ и уезжает
 * в конец по общему правилу. Определения полей сюда не передаются намеренно: сортировке нужно
 * значение поля, а не его описание, и запрос по несуществующему `fieldId` обязан дать «пусто у всех»
 * (то есть порядок по вторичному ключу), а не падение.
 *
 * Чистая функция без Android — покрыта JVM-тестом.
 */
fun sortRecords(
    records: List<RecordWithValues>,
    sort: RecordSort,
    contractors: List<ContractorEntity>,
): List<RecordWithValues> {
    val primary: Comparator<RecordWithValues> = when (val key = sort.key) {
        SortKey.Date -> Comparator { a, b ->
            val byDate = a.record.dateEpochDay.compareTo(b.record.dateEpochDay)
            if (sort.desc) -byDate else byDate
        }
        SortKey.Contractor -> {
            val nameById = contractors.associate { it.id to it.name }
            Comparator { a, b ->
                compareCells(
                    nameById[a.record.contractorId].orEmpty(),
                    nameById[b.record.contractorId].orEmpty(),
                    sort.desc,
                )
            }
        }
        is SortKey.Field -> Comparator { a, b ->
            compareCells(a.valueOf(key.fieldId), b.valueOf(key.fieldId), sort.desc)
        }
    }
    return records.sortedWith(primary.thenBy { it.record.dateEpochDay }.thenBy { it.record.createdAt })
}

/**
 * Сравнение текстовых ключей столбца — единственное место, где записано правило «пустые в конец».
 *
 * Пустое значение уходит вниз при обоих направлениях: незаполненная ячейка не «самая большая» и не
 * «самая маленькая», сравнивать её не с чем. Разворот сортировки прораб делает, чтобы увидеть другой
 * край заполненных данных, а не чтобы получить экран пустых строк — так же ведут себя Excel и Sheets.
 */
private fun compareCells(a: String, b: String, desc: Boolean): Int = when {
    a.isEmpty() && b.isEmpty() -> 0
    a.isEmpty() -> 1
    b.isEmpty() -> -1
    desc -> String.CASE_INSENSITIVE_ORDER.compare(b, a)
    else -> String.CASE_INSENSITIVE_ORDER.compare(a, b)
}
