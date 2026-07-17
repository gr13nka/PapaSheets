package ru.papasheets.matrixgrid

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.floor

/**
 * Результат hit-теста экранной точки. Геометрия ничего не знает о модели — она возвращает лишь
 * координаты в сетке и суб-зону; сопоставление с записью (заполнена/пуста, recordId) делает [MatrixView].
 */
internal sealed interface MatrixHit {
    /** Точка в теле матрицы. [onPhoto] — попадание в Ф-подколонку (фото-зона ячейки). */
    data class Body(val row: Int, val group: Int, val onPhoto: Boolean) : MatrixHit
    /** Шапка, колонка дат, угол или пустое поле за границей мира — в M3 колбэков не порождают. */
    data object Other : MatrixHit
}

/**
 * Фиксированная раскладка матрицы в пикселях (dp пересчитаны через [Density] один раз в конструкторе).
 * Всё за O(1): координата ↔ строка/группа, видимый диапазон, hit-тест. Экземпляр зависит только от
 * плотности и размеров модели — при неизменной модели переживает кадры прокрутки без пересоздания.
 *
 * Зум в M3 не участвует (масштаб 1). Класс намеренно оставлен «плоским» на пиксели zoom=1: под LOD/пинч
 * (M4) достаточно будет умножить базовые размеры на масштаб при создании новой геометрии на ярус.
 */
internal class MatrixGeometry(density: Density, val rowCount: Int, val groupCount: Int) {
    // Фиксированные размеры на zoom=1.
    val dateColW: Float
    val nameRowH: Float
    val subHeaderH: Float
    val headerH: Float
    val rowH: Float

    /** Подколонки внутри группы подрядчика: Ф (фото) | Л (локация) | ВИД РАБОТ. */
    val photoColW: Float
    val locColW: Float
    val workColW: Float
    val groupW: Float

    /** Квадратная фото-плитка, вписанная в Ф-подколонку с отступами. */
    val photoBoxPx: Float
    val photoPadX: Float
    val photoPadY: Float

    /** Общий внутренний отступ текста/содержимого ячеек и шапок. */
    val cellPad: Float

    val worldWidth: Float
    val worldHeight: Float

    init {
        with(density) {
            dateColW = 64.dp.toPx()
            nameRowH = 28.dp.toPx()
            subHeaderH = 20.dp.toPx()
            headerH = nameRowH + subHeaderH
            rowH = 76.dp.toPx()

            photoColW = 72.dp.toPx()
            locColW = 56.dp.toPx()
            workColW = 168.dp.toPx()
            groupW = photoColW + locColW + workColW

            photoBoxPx = 64.dp.toPx()
            cellPad = 6.dp.toPx()
        }
        photoPadX = (photoColW - photoBoxPx) / 2f
        photoPadY = (rowH - photoBoxPx) / 2f
        worldWidth = groupW * groupCount
        worldHeight = rowH * rowCount
    }

    fun bodyWidth(viewportW: Float): Float = (viewportW - dateColW).coerceAtLeast(0f)
    fun bodyHeight(viewportH: Float): Float = (viewportH - headerH).coerceAtLeast(0f)
    fun maxPanX(viewportW: Float): Float = (worldWidth - bodyWidth(viewportW)).coerceAtLeast(0f)
    fun maxPanY(viewportH: Float): Float = (worldHeight - bodyHeight(viewportH)).coerceAtLeast(0f)

    fun firstVisibleRow(panY: Float): Int =
        floor(panY / rowH).toInt().coerceIn(0, (rowCount - 1).coerceAtLeast(0))

    fun lastVisibleRow(panY: Float, bodyHeight: Float): Int =
        floor((panY + bodyHeight) / rowH).toInt().coerceIn(0, (rowCount - 1).coerceAtLeast(0))

    fun firstVisibleGroup(panX: Float): Int =
        floor(panX / groupW).toInt().coerceIn(0, (groupCount - 1).coerceAtLeast(0))

    fun lastVisibleGroup(panX: Float, bodyWidth: Float): Int =
        floor((panX + bodyWidth) / groupW).toInt().coerceIn(0, (groupCount - 1).coerceAtLeast(0))

    /** Экранный X левого края группы [group] с учётом закреплённой колонки дат. */
    fun groupScreenLeft(group: Int, panX: Float): Float = dateColW - panX + group * groupW

    /** Экранный Y верхнего края строки [row] с учётом закреплённой шапки. */
    fun rowScreenTop(row: Int, panY: Float): Float = headerH - panY + row * rowH

    fun hitTest(x: Float, y: Float, panX: Float, panY: Float): MatrixHit {
        val inHeader = y < headerH
        val inDateCol = x < dateColW
        if (inHeader || inDateCol) return MatrixHit.Other
        if (rowCount == 0 || groupCount == 0) return MatrixHit.Other

        val worldX = x - dateColW + panX
        val worldY = y - headerH + panY
        if (worldX < 0f || worldX >= worldWidth || worldY < 0f || worldY >= worldHeight) return MatrixHit.Other

        val group = (worldX / groupW).toInt()
        val row = (worldY / rowH).toInt()
        val localX = worldX - group * groupW
        return MatrixHit.Body(row = row, group = group, onPhoto = localX < photoColW)
    }
}
