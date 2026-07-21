package ru.papasheets.matrixgrid

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Результат hit-теста экранной точки. Геометрия ничего не знает о модели — она возвращает лишь
 * координаты в сетке и суб-зону; сопоставление с записью (заполнена/пуста, recordId) делает [MatrixView].
 */
internal sealed interface MatrixHit {
    /**
     * Точка в теле матрицы. [onPhoto] — попадание в Ф-подколонку (фото-зона ячейки). [photoSlot] —
     * какая половина этой подколонки (0 — левая, 1 — правая): у записи с двумя фото так выбирается,
     * какое открыть. Осмыслен только при [onPhoto]; вызывающая сторона всё равно зажимает его по
     * фактическому числу фото — геометрия про содержимое ячейки не знает.
     */
    data class Body(val row: Int, val group: Int, val onPhoto: Boolean, val photoSlot: Int) : MatrixHit
    /** Шапка, колонка дат, угол или пустое поле за границей мира. */
    data object Other : MatrixHit
}

/** Плитка превью внутри Ф-подколонки (базовые единицы, левый край группы = 0). */
internal class PhotoTile(val left: Float, val top: Float, val size: Float)

/**
 * Наибольший зум, ещё НЕ попадающий в LOD0. Потолок обзорной ветки [MatrixGeometry.fitZoom]: ровно
 * на пороге ярус переключился бы на детальный, а вместе с ним и метрики строк.
 */
private val OVERVIEW_ZOOM_CEILING: Float = Math.nextDown(Lod.DETAIL_MIN_ZOOM)

/**
 * Раскладка матрицы, параметризованная масштабом. Базовые размеры (dp пересчитаны через [Density]
 * один раз) — это мир при zoom = 1; любой экранный размер получается умножением на текущий зум.
 * Экземпляр зависит только от плотности и модели — при неизменной модели переживает кадры
 * пинча/прокрутки без пересоздания.
 *
 * Единицы pan — экранные пиксели уже с учётом зума: [groupScreenLeft]/[rowScreenTop] отсчитывают
 * от начала мира внутри тела. Все клампы ([clampPanX]/[clampPanY]/[clampZoom]) и центрирование
 * (когда масштабированный мир меньше тела, pan запирается в его середину) считаются здесь, чтобы и
 * рендерер, и жесты видели ровно одну версию математики.
 *
 * Толщина закреплённых шапок масштабируется по своей оси вместе с зумом, но не падает ниже
 * минимально читаемой ([dateColW]/[headerH] клампятся снизу). Тело виртуализируется по видимому
 * диапазону, а какой ярус детализации рисовать — решает [Lod] по зуму.
 *
 * **Ось X — ровная сетка, и это инвариант.** Подколонки ([GridModel.fields]) одни и те же во всех
 * группах, поэтому [groupW] — константа, а «координата → группа» остаётся делением за O(1) при любом
 * числе подрядчиков. Переменной по X геометрия не становится: меняется только состав подколонок
 * ВНУТРИ группы, а он один на всю матрицу.
 *
 * **Ось Y зависит от яруса.** На LOD0 строка растёт под текст ([RowMetrics.Variable]), на LOD1/LOD2
 * «картина месяца» обязана оставаться ровной сеткой — там всегда [RowMetrics.Uniform] базовой
 * высоты. Поэтому все Y-методы принимают зум и выбирают метрики через [metrics]: на пороге LOD0↔LOD1
 * мир скачком меняет высоту, и клампы pan обязаны считаться уже по новым метрикам — иначе прокрутка
 * прыгнет. Единственный источник этого выбора — [metrics], так что рендерер и жесты не разъезжаются.
 */
internal class MatrixGeometry(
    density: Density,
    /** Подколонки внутри группы подрядчика (после Ф). */
    val fields: List<GridField>,
    val groupCount: Int,
    /** Метрики строк детального яруса; на LOD1/LOD2 подменяются ровной сеткой. */
    rowMetrics: RowMetrics,
) {
    // Базовые размеры на zoom = 1.
    val dateColWBase: Float
    val nameRowHBase: Float
    val subHeaderHBase: Float
    val headerHBase: Float

    /** Базовая (минимальная) высота строки: высота строки на LOD1/LOD2 и нижняя граница на LOD0. */
    val rowH: Float

    /** Фото-подколонка Ф — всегда первая в группе; остальные описаны в [fields]. */
    val photoColW: Float
    val groupW: Float

    /**
     * Левые границы подколонок внутри группы: `[0] == photoColW`, дальше префикс-суммы ширин полей,
     * последний элемент == [groupW].
     */
    private val fieldOffsets: FloatArray

    /** Квадратная фото-плитка, вписанная в Ф-подколонку с отступами (базовый размер). */
    val photoBoxPx: Float
    val photoPadX: Float

    /** Общий внутренний отступ текста/содержимого ячеек и шапок (базовый). */
    val cellPad: Float

    /** Минимально читаемые размеры шапок — нижний кламп при сильном отдалении. */
    val dateColMinW: Float
    val headerMinH: Float

    val worldWidth: Float

    /** Метрики LOD0 (строки растянуты под текст) и ровная сетка LOD1/LOD2. */
    private val detailed: RowMetrics
    private val flat: RowMetrics

    val rowCount: Int get() = detailed.rowCount

    init {
        with(density) {
            dateColWBase = 64.dp.toPx()
            nameRowHBase = 28.dp.toPx()
            subHeaderHBase = 20.dp.toPx()
            headerHBase = nameRowHBase + subHeaderHBase
            rowH = ROW_MIN_H.toPx()

            photoColW = 72.dp.toPx()
            fieldOffsets = FloatArray(fields.size + 1)
            fieldOffsets[0] = photoColW
            for (i in fields.indices) fieldOffsets[i + 1] = fieldOffsets[i] + fields[i].widthDp.dp.toPx()

            photoBoxPx = 64.dp.toPx()
            cellPad = 6.dp.toPx()

            dateColMinW = 34.dp.toPx()
            headerMinH = 30.dp.toPx()
        }
        groupW = fieldOffsets[fields.size]
        photoPadX = (photoColW - photoBoxPx) / 2f
        worldWidth = groupW * groupCount
        detailed = rowMetrics
        // Уже ровные метрики переиспользуем как есть: тогда на типичном журнале оба яруса ссылаются
        // на ОДИН объект, и переход через порог LOD не меняет ни высоту мира, ни pan (см. [metrics]).
        flat = if (rowMetrics is RowMetrics.Uniform) rowMetrics else RowMetrics.Uniform(rowMetrics.rowCount, rowH)
    }

    // --- Плитки превью внутри Ф-подколонки. ---

    /**
     * Прямоугольники превью для записи с [photoCount] фото (базовые единицы). Одно фото — полный
     * квадрат [photoBoxPx] по центру подколонки, ровно как было до второго слота. Два — два равных
     * бокса рядом с зазором в [photoPadX]: каждый мельче, зато оба видны целиком, а Ф-подколонка не
     * расширяется (её ширина — константа модели, на ней держится вся виртуализация по X).
     *
     * [photoCount] зажимается сверху [MAX_PHOTOS_PER_CELL]: больше плиток в подколонку не поместить,
     * и лишние молча отбрасываются здесь, а не наезжают на соседнюю группу.
     */
    fun photoTiles(photoCount: Int): List<PhotoTile> {
        if (photoCount <= 1) return listOf(PhotoTile(photoPadX, cellPad, photoBoxPx))
        val boxes = MAX_PHOTOS_PER_CELL
        val size = (photoColW - 2 * photoPadX - (boxes - 1) * photoPadX) / boxes
        return (0 until boxes).map { slot ->
            PhotoTile(left = photoPadX + slot * (size + photoPadX), top = cellPad, size = size)
        }
    }

    // --- Подколонки внутри группы (ось X). ---

    val fieldCount: Int get() = fields.size

    /** Смещение левого края поля [index] от левого края группы. */
    fun fieldLeft(index: Int): Float = fieldOffsets[index]

    fun fieldWidth(index: Int): Float = fieldOffsets[index + 1] - fieldOffsets[index]

    // --- Выбор метрик строк по ярусу (ось Y). ---

    /**
     * Метрики строк на данном зуме. На пороге LOD0↔LOD1 высота мира меняется скачком — все Y-клампы и
     * экранные координаты считаются уже по возвращённым здесь метрикам, поэтому кадр остаётся
     * согласованным сам с собой, а не смешивает высоты двух ярусов.
     */
    private fun metrics(zoom: Float): RowMetrics =
        if (Lod.forZoom(zoom) == Lod.LOD0) detailed else flat

    fun worldHeight(zoom: Float): Float = metrics(zoom).totalHeight

    /** Базовая (zoom = 1) высота строки [row] на этом ярусе. */
    fun rowHeight(row: Int, zoom: Float): Float = metrics(zoom).heightOf(row)

    // --- Масштаб шапок: линейно с зумом, но не ниже минимально читаемого. ---

    /** Множитель ширины колонки дат и кегля её подписей: max(zoom, порог читаемости). */
    fun dateColScale(zoom: Float): Float = max(zoom, dateColMinW / dateColWBase)

    /** Множитель толщины шапки подрядчиков и кегля её подписей. */
    fun headerScale(zoom: Float): Float = max(zoom, headerMinH / headerHBase)

    fun dateColW(zoom: Float): Float = dateColWBase * dateColScale(zoom)
    fun headerH(zoom: Float): Float = headerHBase * headerScale(zoom)
    fun nameRowH(zoom: Float): Float = nameRowHBase * headerScale(zoom)
    fun subHeaderH(zoom: Float): Float = subHeaderHBase * headerScale(zoom)

    fun bodyWidth(viewportW: Float, zoom: Float): Float = (viewportW - dateColW(zoom)).coerceAtLeast(0f)
    fun bodyHeight(viewportH: Float, zoom: Float): Float = (viewportH - headerH(zoom)).coerceAtLeast(0f)

    // --- Зум-диапазон. ---

    /**
     * Масштаб, при котором весь мир вместе с шапками помещается в тело вьюпорта, но не крупнее 1.0.
     *
     * Высота мира зависит от яруса, поэтому сперва проверяем детальный: если мир LOD0 влезает на
     * зуме, который сам попадает в LOD0, — это и есть ответ. Иначе fit заведомо лежит на обзорных
     * ярусах, где сетка ровная, и считается по ней. Когда автовысоты нет (метрики совпадают), обе
     * ветки дают одно и то же число — ровно то, что было до автовысоты.
     *
     * Вторая ветка обязана остаться ниже порога LOD0, даже когда ровный мир влезает и крупнее:
     * на детальных метриках он там уже не влезает, а [fitZoom] — нижняя граница [clampZoom], так что
     * завышенная граница заперла бы зум-аут насовсем. Случай достижимый: короткий журнал с длинными
     * текстами (ровная сетка влезает на 1.0, растянутая — нет).
     */
    fun fitZoom(viewportW: Float, viewportH: Float): Float {
        val byDetailed = fitFor(detailed, viewportW, viewportH)
        if (Lod.forZoom(byDetailed) == Lod.LOD0) return byDetailed
        return min(fitFor(flat, viewportW, viewportH), OVERVIEW_ZOOM_CEILING)
    }

    /**
     * Fit при фиксированных метриках строк. Считается в два прохода: первая оценка — при минимальных
     * (клампленных) шапках, вторая уточняет с фактической толщиной шапок на этой оценке (толщина
     * монотонна по зуму, одного прохода хватает).
     */
    private fun fitFor(rows: RowMetrics, viewportW: Float, viewportH: Float): Float {
        val worldHeight = rows.totalHeight
        if (worldWidth <= 0f || worldHeight <= 0f) return 1f
        val rough = min(
            (viewportW - dateColMinW) / worldWidth,
            (viewportH - headerMinH) / worldHeight,
        ).coerceIn(Lod.MIN_ZOOM_FLOOR, 1f)
        val refined = min(
            (viewportW - dateColW(rough)) / worldWidth,
            (viewportH - headerH(rough)) / worldHeight,
        )
        return refined.coerceIn(Lod.MIN_ZOOM_FLOOR, 1f)
    }

    fun clampZoom(zoom: Float, viewportW: Float, viewportH: Float): Float =
        zoom.coerceIn(fitZoom(viewportW, viewportH), Lod.MAX_ZOOM)

    // --- Границы pan с центрированием (экранные px). ---

    fun minPanX(viewportW: Float, zoom: Float): Float {
        val free = worldWidth * zoom - bodyWidth(viewportW, zoom)
        return if (free >= 0f) 0f else free / 2f
    }

    fun maxPanX(viewportW: Float, zoom: Float): Float {
        val free = worldWidth * zoom - bodyWidth(viewportW, zoom)
        return if (free >= 0f) free else free / 2f
    }

    fun minPanY(viewportH: Float, zoom: Float): Float {
        val free = worldHeight(zoom) * zoom - bodyHeight(viewportH, zoom)
        return if (free >= 0f) 0f else free / 2f
    }

    fun maxPanY(viewportH: Float, zoom: Float): Float {
        val free = worldHeight(zoom) * zoom - bodyHeight(viewportH, zoom)
        return if (free >= 0f) free else free / 2f
    }

    fun clampPanX(panX: Float, viewportW: Float, zoom: Float): Float =
        panX.coerceIn(minPanX(viewportW, zoom), maxPanX(viewportW, zoom))

    fun clampPanY(panY: Float, viewportH: Float, zoom: Float): Float =
        panY.coerceIn(minPanY(viewportH, zoom), maxPanY(viewportH, zoom))

    // --- Пивот пинча: pan, при котором мировая точка под экранной точкой не сдвигается при смене зума. ---

    fun panXForZoom(pivotX: Float, panX: Float, zoomOld: Float, zoomNew: Float): Float {
        val worldX = (pivotX - dateColW(zoomOld) + panX) / zoomOld
        return dateColW(zoomNew) + worldX * zoomNew - pivotX
    }

    /**
     * Y-пивот с поправкой на смену яруса: мировая координата под пальцем берётся в метриках ИСХОДНОГО
     * зума, а экранная считается в метриках НОВОГО. Если пинч пересёк порог LOD0↔LOD1, «та же» мировая
     * координата в новых метриках указывала бы на другую строку — поэтому точка переносится как
     * «строка + доля внутри строки», и под пальцем остаётся то же место той же строки.
     */
    fun panYForZoom(pivotY: Float, panY: Float, zoomOld: Float, zoomNew: Float): Float {
        val worldY = (pivotY - headerH(zoomOld) + panY) / zoomOld
        val remapped = remapWorldY(worldY, metrics(zoomOld), metrics(zoomNew))
        return headerH(zoomNew) + remapped * zoomNew - pivotY
    }

    private fun remapWorldY(y: Float, from: RowMetrics, to: RowMetrics): Float {
        if (from === to || from.rowCount == 0) return y
        val row = from.rowAt(y)
        val height = from.heightOf(row)
        val fraction = if (height > 0f) (y - from.topOf(row)) / height else 0f
        return to.topOf(row) + fraction * to.heightOf(row)
    }

    // --- Виртуализация и экранные координаты (с учётом зума). ---

    fun firstVisibleRow(panY: Float, zoom: Float): Int = metrics(zoom).rowAt(panY / zoom)

    fun lastVisibleRow(panY: Float, bodyHeight: Float, zoom: Float): Int =
        metrics(zoom).rowAt((panY + bodyHeight) / zoom)

    fun firstVisibleGroup(panX: Float, zoom: Float): Int =
        floor(panX / (groupW * zoom)).toInt().coerceIn(0, (groupCount - 1).coerceAtLeast(0))

    fun lastVisibleGroup(panX: Float, bodyWidth: Float, zoom: Float): Int =
        floor((panX + bodyWidth) / (groupW * zoom)).toInt().coerceIn(0, (groupCount - 1).coerceAtLeast(0))

    /** Экранный X левого края группы [group] с учётом закреплённой (клампленной) колонки дат. */
    fun groupScreenLeft(group: Int, panX: Float, zoom: Float): Float =
        dateColW(zoom) - panX + group * groupW * zoom

    /** Экранный Y верхнего края строки [row] с учётом закреплённой (клампленной) шапки. */
    fun rowScreenTop(row: Int, panY: Float, zoom: Float): Float =
        headerH(zoom) - panY + metrics(zoom).topOf(row) * zoom

    fun hitTest(x: Float, y: Float, panX: Float, panY: Float, zoom: Float): MatrixHit {
        val headerH = headerH(zoom)
        val dateColW = dateColW(zoom)
        if (y < headerH || x < dateColW) return MatrixHit.Other
        if (rowCount == 0 || groupCount == 0) return MatrixHit.Other

        val worldX = x - dateColW + panX
        val worldY = y - headerH + panY
        val scaledWorldW = worldWidth * zoom
        val scaledWorldH = worldHeight(zoom) * zoom
        if (worldX < 0f || worldX >= scaledWorldW || worldY < 0f || worldY >= scaledWorldH) return MatrixHit.Other

        val groupPx = groupW * zoom
        val group = (worldX / groupPx).toInt()
        val row = metrics(zoom).rowAt(worldY / zoom)
        val localX = worldX - group * groupPx
        // Фото-зона (тап → лайтбокс) есть только там, где превью реально рисуются (LOD0/LOD1); на LOD2
        // «картина месяца» без битмапов — попадание в Ф-подколонку = обычный тап по ячейке.
        val photoColPx = photoColW * zoom
        val onPhoto = Lod.forZoom(zoom) != Lod.LOD2 && localX < photoColPx
        // Половина подколонки → слот. Зажатие по реальному числу фото — на стороне [MatrixView].
        val photoSlot = if (localX < photoColPx / 2f) 0 else 1
        return MatrixHit.Body(row = row, group = group, onPhoto = onPhoto, photoSlot = photoSlot)
    }
}
