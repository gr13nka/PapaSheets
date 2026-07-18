package ru.papasheets.ui.journal

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.papasheets.R
import ru.papasheets.data.db.entity.ContractorEntity
import ru.papasheets.data.db.entity.FieldDefEntity
import ru.papasheets.data.db.entity.JournalEntity
import ru.papasheets.data.repo.ContractorRepository
import ru.papasheets.data.repo.FieldRepository
import ru.papasheets.data.repo.JournalRepository
import ru.papasheets.data.repo.RecordRepository
import ru.papasheets.data.repo.ValueSuggester
import ru.papasheets.domain.JournalDates
import ru.papasheets.domain.JournalFilter
import ru.papasheets.domain.JournalQuery
import ru.papasheets.domain.RecordSort
import ru.papasheets.domain.SortKey
import ru.papasheets.domain.ViewMode
import ru.papasheets.domain.applyFilter
import ru.papasheets.domain.buildGridModel
import ru.papasheets.domain.export.ExportFormat
import ru.papasheets.domain.export.ExportInteractor
import ru.papasheets.domain.sortRecords
import ru.papasheets.matrixgrid.GridModel
import ru.papasheets.photos.BitmapThumbnailSource
import ru.papasheets.photos.PhotoStore

private const val TAG = "JournalViewModel"

// Веса дат и подрядчика в той же шкале, что и ширины полей (dp колонки матрицы).
private const val DATE_COLUMN_WEIGHT = 56f
private const val CONTRACTOR_COLUMN_WEIGHT = 88f

/** Итог одной попытки экспорта — одноразовое событие для тоста в UI (см. [JournalViewModel.exportEvents]). */
sealed interface ExportEvent {
    data object Success : ExportEvent
    data class Failure(val message: String) : ExportEvent
}

/**
 * Одна строка вида «Список»: всё, что она рисует, уже готово — сама строка ничего не ищет
 * и не форматирует.
 *
 * [cells] лежат ровно в порядке [JournalContent.columns] и той же длины: дата, подрядчик, значения
 * активных полей. Одна общая последовательность вместо отдельных полей под дату и подрядчика — чтобы
 * шапка и содержимое не могли разъехаться: столбец и ячейка соединяются по индексу, а собраны они
 * рядом, в одном выражении.
 */
data class RecordRow(
    val recordId: String,
    val cells: List<String>,
    /** Цвет подрядчика — метка строки без фото; в столбцы не входит. */
    val contractorColorIndex: Int,
    val photoId: String?,
)

/**
 * Столбец вида «Список»: подпись в шапке, ключ сортировки и доля ширины экрана.
 *
 * [weight] у полей — это их ширина колонки в матрице (`columnWidthDp`), взятая как относительный
 * вес: прораб уже настроил, что «Вид работ» втрое шире «Локации», и второго набора ширин, который
 * пришлось бы держать в согласии с первым, заводить незачем.
 */
data class ListColumn(val title: String, val key: SortKey, val weight: Float)

/**
 * Готовое содержимое экрана в обоих видах, посчитанное из ОДНОЙ отфильтрованной выборки.
 *
 * Оба вида собираются всегда, а не по текущему [ViewMode]: раскладка — чистая арифметика над сотнями
 * записей, её цена ничтожна рядом с гарантией, что переключение вида не может показать другой набор
 * записей. Развилка «считаем только видимый вид» вернула бы ровно ту двойственность, ради устранения
 * которой фильтр вынесен в [applyFilter].
 */
data class JournalContent(
    val grid: GridModel,
    val columns: List<ListColumn>,
    val rows: List<RecordRow>,
) {
    val isEmpty: Boolean get() = rows.isEmpty()
}

/**
 * Модель экрана журнала. Держит потоки записей, подрядчиков, полей и текущий [JournalQuery] и сшивает
 * их в [JournalContent] на [Dispatchers.Default] (раскладка сотен строк не должна дёргать главный
 * поток). Подрядчики берутся через [ContractorRepository.observeAll] (не только активные) — архивный
 * с записями в этом журнале остаётся колонкой, решает об этом уже [buildGridModel]. [thumbnails]
 * живёт столько же, сколько ViewModel — его LRU переживает рекомпозиции экрана.
 *
 * Фильтр и сортировка живут здесь, а не в состоянии композиции: они переживают поворот экрана вместе
 * с ViewModel, и их не нужно сериализовать.
 */
class JournalViewModel(
    private val journalId: String,
    journalRepository: JournalRepository,
    private val recordRepository: RecordRepository,
    contractorRepository: ContractorRepository,
    private val fieldRepository: FieldRepository,
    private val valueSuggester: ValueSuggester,
    photoStore: PhotoStore,
    private val exportInteractor: ExportInteractor,
    appContext: Context,
) : ViewModel() {

    val thumbnails = BitmapThumbnailSource(photoStore, viewModelScope, appContext)

    // Подписи двух столбцов, которых нет среди полей: дата и подрядчик есть у каждой записи
    // независимо от набора полей, поэтому их названия — ресурсы, а не строки field_defs.
    private val dateColumnTitle = appContext.getString(R.string.journal_column_date)
    private val contractorColumnTitle = appContext.getString(R.string.journal_column_contractor)

    override fun onCleared() {
        thumbnails.dispose()
    }

    val journal: StateFlow<JournalEntity?> = journalRepository.observeById(journalId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _query = MutableStateFlow(JournalQuery())
    val query: StateFlow<JournalQuery> = _query.asStateFlow()

    /** Подрядчики для панели фильтра — все, включая архивных: их записи в старом журнале никуда не делись. */
    val contractors: StateFlow<List<ContractorEntity>> = contractorRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Активные поля — строки панели фильтра. */
    val fields: StateFlow<List<FieldDefEntity>> = fieldRepository.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // null до первой готовой раскладки — экран показывает загрузку.
    val content: StateFlow<JournalContent?> = combine(
        recordRepository.observeByJournal(journalId),
        contractorRepository.observeAll(),
        fieldRepository.observeActive(),
        _query,
    ) { records, contractors, fields, query ->
        withContext(Dispatchers.Default) {
            val filtered = applyFilter(records, query.filter)
            val contractorsById = contractors.associateBy { it.id }
            JournalContent(
                grid = buildGridModel(filtered, contractors, fields, query.sort.matrixDatesDesc),
                columns = listOf(
                    ListColumn(dateColumnTitle, SortKey.Date, DATE_COLUMN_WEIGHT),
                    ListColumn(contractorColumnTitle, SortKey.Contractor, CONTRACTOR_COLUMN_WEIGHT),
                ) + fields.map { ListColumn(it.title, SortKey.Field(it.id), it.columnWidthDp.toFloat()) },
                rows = sortRecords(filtered, query.sort, contractors).map { entry ->
                    val contractor = contractorsById[entry.record.contractorId]
                    RecordRow(
                        recordId = entry.record.id,
                        cells = listOf(
                            JournalDates.shortMonth(LocalDate.ofEpochDay(entry.record.dateEpochDay)),
                            contractor?.name.orEmpty(),
                        ) + fields.map { entry.valueOf(it.id) },
                        contractorColorIndex = contractor?.colorIndex ?: 0,
                        photoId = entry.record.photoId,
                    )
                },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setViewMode(mode: ViewMode) = _query.update { it.copy(viewMode = mode) }

    /**
     * Тумблер ↑↓ матрицы. Он переставляет именно даты, поэтому заодно возвращает ключ сортировки к
     * [SortKey.Date]: иначе после сортировки списка по локации кнопка в матрице первым нажатием
     * ничего бы не изменила (см. [RecordSort.matrixDatesDesc]).
     */
    fun toggleDateOrder() = _query.update {
        it.copy(sort = RecordSort(SortKey.Date, desc = !it.sort.matrixDatesDesc))
    }

    /** Тап по шапке столбца списка: чужой столбец — сортировка по нему, свой — разворот направления. */
    fun sortBy(key: SortKey) = _query.update {
        it.copy(sort = if (it.sort.key == key) it.sort.copy(desc = !it.sort.desc) else RecordSort(key, desc = false))
    }

    fun setFilter(filter: JournalFilter) = _query.update { it.copy(filter = filter) }

    fun clearFilter() = _query.update { it.copy(filter = JournalFilter()) }

    /**
     * Значения, из которых собираются чипы фильтра по полю, — та же выборка, что кормит
     * автодополнение в форме записи ([ValueSuggester.usedValues]): фильтр предлагает ровно то, что
     * в поле уже написано, и не может предложить вариант, дающий пустой экран. Заодно наследует её
     * потолок в 15 значений — для выбора мышью список длиннее и не нужен.
     *
     * Читается по требованию (открытие панели фильтра), а не потоком: это разовый снимок истории,
     * и держать под него подписку незачем.
     */
    private val _filterOptions = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val filterOptions: StateFlow<Map<String, List<String>>> = _filterOptions.asStateFlow()

    fun refreshFilterOptions() {
        viewModelScope.launch {
            _filterOptions.value = fieldRepository.observeActive().first()
                .associate { it.id to valueSuggester.usedValues(it.id) }
        }
    }

    fun deleteRecord(recordId: String) {
        viewModelScope.launch {
            recordRepository.getById(recordId)?.let { recordRepository.delete(it) }
        }
    }

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    private val _exportEvents = MutableSharedFlow<ExportEvent>(extraBufferCapacity = 1)
    val exportEvents: SharedFlow<ExportEvent> = _exportEvents.asSharedFlow()

    /** Имя файла по умолчанию для SAF `CreateDocument` — показывает [ru.papasheets.ui.export.ExportDialog]. */
    fun defaultExportFileName(format: ExportFormat): String =
        exportInteractor.defaultFileName(journal.value?.title ?: "Журнал", format)

    fun exportTo(uri: Uri, format: ExportFormat) {
        if (_exporting.value) return
        viewModelScope.launch {
            _exporting.value = true
            try {
                exportInteractor.export(journalId, format, uri)
                _exportEvents.emit(ExportEvent.Success)
            } catch (e: Exception) {
                Log.e(TAG, "export failed: format=$format", e)
                _exportEvents.emit(ExportEvent.Failure(e.message ?: "Не удалось экспортировать"))
            } finally {
                _exporting.value = false
            }
        }
    }
}
