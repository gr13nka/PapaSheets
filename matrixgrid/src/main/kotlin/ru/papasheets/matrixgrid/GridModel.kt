package ru.papasheets.matrixgrid

/**
 * Иммутабельная модель матрицы «дата × подрядчик» — единственный вход данных для [MatrixView].
 *
 * Модуль matrixgrid ничего не знает о Room, файлах и Coil: приходит готовая раскладка
 * (строится в app/GridModelBuilder), картинки поступают через [ThumbnailSource] по ключу.
 * Смена модели — единственный повод для рекомпозиции матрицы; pan/zoom живут в MatrixState
 * и до рекомпозиции не дотрагиваются.
 */
class GridModel(
    /** Порядок списка = порядок групп колонок слева направо. */
    val contractors: List<ContractorColumn>,
    /** Все строки в порядке отображения (сортировку по дате решает builder). */
    val rows: List<GridRow>,
) {
    /** Стабильный ключ содержимого — позволяет пропускать пересоздание кэшей при равных моделях. */
    val contentKey: Int = rows.size * 31 + contractors.size
}

class ContractorColumn(
    val id: String,
    val name: String,
    /** Короткое имя для шапки на сильном отдалении (LOD2). */
    val shortName: String,
    /** Индекс в палитре модуля (см. ContractorPalette, M4) — цвет блоков LOD2 и акцентов. */
    val colorIndex: Int,
)

class GridRow(
    val dateEpochDay: Long,
    /** Подпись даты в закреплённой колонке, например «17 июл»; рисуется только при [isFirstOfDay]. */
    val dayLabel: String,
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
