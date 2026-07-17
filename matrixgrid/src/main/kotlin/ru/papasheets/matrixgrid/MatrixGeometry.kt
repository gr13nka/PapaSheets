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
    /** Точка в теле матрицы. [onPhoto] — попадание в Ф-подколонку (фото-зона ячейки). */
    data class Body(val row: Int, val group: Int, val onPhoto: Boolean) : MatrixHit
    /** Шапка, колонка дат, угол или пустое поле за границей мира. */
    data object Other : MatrixHit
}

/**
 * Раскладка матрицы, параметризованная масштабом. Базовые размеры (dp пересчитаны через [Density]
 * один раз) — это мир при zoom = 1; любой экранный размер получается умножением на текущий зум, а
 * координата ↔ ячейка остаётся O(1). Экземпляр зависит только от плотности и размеров модели — при
 * неизменной модели переживает кадры пинча/прокрутки без пересоздания.
 *
 * Единицы pan — экранные пиксели уже с учётом зума: [groupScreenLeft]/[rowScreenTop] отсчитывают
 * от начала мира внутри тела. Все клампы ([clampPanX]/[clampPanY]/[clampZoom]) и центрирование
 * (когда масштабированный мир меньше тела, pan запирается в его середину) считаются здесь, чтобы и
 * рендерер, и жесты видели ровно одну версию математики.
 *
 * Толщина закреплённых шапок масштабируется по своей оси вместе с зумом, но не падает ниже
 * минимально читаемой ([dateColW]/[headerH] клампятся снизу). Тело виртуализируется по видимому
 * диапазону, а какой ярус детализации рисовать — решает [Lod] по зуму.
 */
internal class MatrixGeometry(density: Density, val rowCount: Int, val groupCount: Int) {
    // Базовые размеры на zoom = 1.
    val dateColWBase: Float
    val nameRowHBase: Float
    val subHeaderHBase: Float
    val headerHBase: Float
    val rowH: Float

    /** Подколонки внутри группы подрядчика: Ф (фото) | Л (локация) | ВИД РАБОТ. */
    val photoColW: Float
    val locColW: Float
    val workColW: Float
    val groupW: Float

    /** Квадратная фото-плитка, вписанная в Ф-подколонку с отступами (базовый размер). */
    val photoBoxPx: Float
    val photoPadX: Float
    val photoPadY: Float

    /** Общий внутренний отступ текста/содержимого ячеек и шапок (базовый). */
    val cellPad: Float

    /** Минимально читаемые размеры шапок — нижний кламп при сильном отдалении. */
    val dateColMinW: Float
    val headerMinH: Float

    val worldWidth: Float
    val worldHeight: Float

    init {
        with(density) {
            dateColWBase = 64.dp.toPx()
            nameRowHBase = 28.dp.toPx()
            subHeaderHBase = 20.dp.toPx()
            headerHBase = nameRowHBase + subHeaderHBase
            rowH = 76.dp.toPx()

            photoColW = 72.dp.toPx()
            locColW = 56.dp.toPx()
            workColW = 168.dp.toPx()
            groupW = photoColW + locColW + workColW

            photoBoxPx = 64.dp.toPx()
            cellPad = 6.dp.toPx()

            dateColMinW = 34.dp.toPx()
            headerMinH = 30.dp.toPx()
        }
        photoPadX = (photoColW - photoBoxPx) / 2f
        photoPadY = (rowH - photoBoxPx) / 2f
        worldWidth = groupW * groupCount
        worldHeight = rowH * rowCount
    }

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
     * Считается в два прохода: первая оценка — при минимальных (клампленных) шапках, вторая уточняет
     * с фактической толщиной шапок на этой оценке (толщина монотонна по зуму, одного прохода хватает).
     */
    fun fitZoom(viewportW: Float, viewportH: Float): Float {
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
        val free = worldHeight * zoom - bodyHeight(viewportH, zoom)
        return if (free >= 0f) 0f else free / 2f
    }

    fun maxPanY(viewportH: Float, zoom: Float): Float {
        val free = worldHeight * zoom - bodyHeight(viewportH, zoom)
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

    fun panYForZoom(pivotY: Float, panY: Float, zoomOld: Float, zoomNew: Float): Float {
        val worldY = (pivotY - headerH(zoomOld) + panY) / zoomOld
        return headerH(zoomNew) + worldY * zoomNew - pivotY
    }

    // --- Виртуализация и экранные координаты (с учётом зума). ---

    fun firstVisibleRow(panY: Float, zoom: Float): Int =
        floor(panY / (rowH * zoom)).toInt().coerceIn(0, (rowCount - 1).coerceAtLeast(0))

    fun lastVisibleRow(panY: Float, bodyHeight: Float, zoom: Float): Int =
        floor((panY + bodyHeight) / (rowH * zoom)).toInt().coerceIn(0, (rowCount - 1).coerceAtLeast(0))

    fun firstVisibleGroup(panX: Float, zoom: Float): Int =
        floor(panX / (groupW * zoom)).toInt().coerceIn(0, (groupCount - 1).coerceAtLeast(0))

    fun lastVisibleGroup(panX: Float, bodyWidth: Float, zoom: Float): Int =
        floor((panX + bodyWidth) / (groupW * zoom)).toInt().coerceIn(0, (groupCount - 1).coerceAtLeast(0))

    /** Экранный X левого края группы [group] с учётом закреплённой (клампленной) колонки дат. */
    fun groupScreenLeft(group: Int, panX: Float, zoom: Float): Float =
        dateColW(zoom) - panX + group * groupW * zoom

    /** Экранный Y верхнего края строки [row] с учётом закреплённой (клампленной) шапки. */
    fun rowScreenTop(row: Int, panY: Float, zoom: Float): Float =
        headerH(zoom) - panY + row * rowH * zoom

    fun hitTest(x: Float, y: Float, panX: Float, panY: Float, zoom: Float): MatrixHit {
        val headerH = headerH(zoom)
        val dateColW = dateColW(zoom)
        if (y < headerH || x < dateColW) return MatrixHit.Other
        if (rowCount == 0 || groupCount == 0) return MatrixHit.Other

        val worldX = x - dateColW + panX
        val worldY = y - headerH + panY
        val scaledWorldW = worldWidth * zoom
        val scaledWorldH = worldHeight * zoom
        if (worldX < 0f || worldX >= scaledWorldW || worldY < 0f || worldY >= scaledWorldH) return MatrixHit.Other

        val groupPx = groupW * zoom
        val group = (worldX / groupPx).toInt()
        val row = (worldY / (rowH * zoom)).toInt()
        val localX = worldX - group * groupPx
        // Фото-зона (тап → лайтбокс) есть только там, где превью реально рисуются (LOD0/LOD1); на LOD2
        // «картина месяца» без битмапов — попадание в Ф-подколонку = обычный тап по ячейке.
        val onPhoto = Lod.forZoom(zoom) != Lod.LOD2 && localX < photoColW * zoom
        return MatrixHit.Body(row = row, group = group, onPhoto = onPhoto)
    }
}
