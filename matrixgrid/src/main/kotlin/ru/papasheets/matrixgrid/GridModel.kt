package ru.papasheets.matrixgrid

/**
 * Иммутабельная модель матрицы «дата × подрядчик» — единственный вход данных для [MatrixView].
 *
 * Модуль matrixgrid ничего не знает о Room, файлах и Coil: приходит готовая раскладка
 * (строится в app/GridModelBuilder), картинки поступают через [ThumbnailSource] по ключу.
 * Смена экземпляра модели — единственный повод для рекомпозиции матрицы и сброса кэшей рендерера
 * (инвалидация идёт по идентичности экземпляра, см. [CellTextCache]); pan/zoom живут в [MatrixState]
 * и до рекомпозиции не дотрагиваются.
 */
class GridModel(
    /** Порядок списка = порядок групп колонок слева направо. */
    val contractors: List<ContractorColumn>,
    /** Все строки в порядке отображения (сортировку по дате решает builder). */
    val rows: List<GridRow>,
)

class ContractorColumn(
    val id: String,
    val name: String,
    /** Короткое имя для шапки на сильном отдалении (LOD2). */
    val shortName: String,
    /** Индекс в палитре модуля (см. [ContractorPalette]) — цвет блоков LOD2 и тонировки ячеек. */
    val colorIndex: Int,
    /** Архивный подрядчик, оставленный колонкой только из-за записей в этом журнале — имя в шапке приглушено. */
    val isArchived: Boolean = false,
)

class GridRow(
    val dateEpochDay: Long,
    /** Подпись даты в закреплённой колонке, например «17 июл» (LOD0/LOD1); рисуется только при [isFirstOfDay]. */
    val dayLabel: String,
    /** Только число дня, например «17» — компактная подпись колонки дат на LOD2 («картина месяца»). */
    val dayNumber: String,
    /** Первая строка блока дня: рисует подпись даты и верхнюю границу-разделитель дня. */
    val isFirstOfDay: Boolean,
    /** Ровно contractors.size элементов; null = пустой слот (тап по нему = создание записи). */
    val cells: List<GridCell?>,
)

class GridCell(
    val recordId: String,
    /** Ключ превью для [ThumbnailSource] (photoId) или null, если фото ещё нет. */
    val thumbKey: String?,
    val locationCode: String,
    val workText: String,
)
