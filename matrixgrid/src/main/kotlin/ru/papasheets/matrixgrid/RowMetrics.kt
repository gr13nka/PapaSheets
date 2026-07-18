package ru.papasheets.matrixgrid

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.util.Arrays
import kotlin.math.max
import kotlin.math.roundToInt

/** Базовая (минимальная) высота строки. Строка LOD0 растёт от неё под текст; LOD1/LOD2 — всегда ровно она. */
internal val ROW_MIN_H = 76.dp

/**
 * Раскладка строк по вертикали. Прячет от всей остальной геометрии то, равномерны строки или нет:
 * до автовысоты матрица делила `y / rowH`, и этот быстрый путь никуда не делся — он просто стал
 * одной из реализаций ([Uniform]), а вторая ([Variable]) платит логарифм только там, где строки
 * действительно разной высоты.
 *
 * Координаты — мировые (zoom = 1); домножение на зум делают вызывающие.
 */
internal sealed interface RowMetrics {
    val rowCount: Int
    val totalHeight: Float

    fun heightOf(row: Int): Float
    fun topOf(row: Int): Float

    /** Индекс строки, содержащей мировую координату [y]. Клампится в [0, rowCount-1]. */
    fun rowAt(y: Float): Int

    /** Ровная сетка: строка находится делением, как до появления автовысоты. */
    class Uniform(override val rowCount: Int, val rowH: Float) : RowMetrics {
        override val totalHeight: Float get() = rowH * rowCount
        override fun heightOf(row: Int): Float = rowH
        override fun topOf(row: Int): Float = row * rowH
        override fun rowAt(y: Float): Int =
            if (rowH <= 0f) 0 else (y / rowH).toInt().coerceIn(0, lastRow())
    }

    /**
     * Строки разной высоты. [offsets] — префикс-суммы длиной `rowCount + 1`: `offsets[0] == 0f`,
     * `offsets.last() == totalHeight`; поиск строки по координате — бинарный.
     */
    class Variable(private val offsets: FloatArray) : RowMetrics {
        override val rowCount: Int get() = offsets.size - 1
        override val totalHeight: Float get() = offsets[offsets.size - 1]
        override fun heightOf(row: Int): Float = offsets[row + 1] - offsets[row]
        override fun topOf(row: Int): Float = offsets[row]

        override fun rowAt(y: Float): Int {
            val found = Arrays.binarySearch(offsets, 0, rowCount, y)
            // Точное попадание — это верхняя граница строки found; иначе binarySearch возвращает
            // -(позиция вставки) - 1, и строка, накрывающая y, лежит на одну левее точки вставки.
            val row = if (found >= 0) found else -found - 2
            return row.coerceIn(0, lastRow())
        }
    }

    companion object {
        /** Метрики ровной сетки на [rowCount] строк базовой высоты. */
        fun uniform(density: Density, rowCount: Int): RowMetrics =
            Uniform(rowCount, with(density) { ROW_MIN_H.toPx() })

        /**
         * Собирает метрики по посчитанным высотам, сворачиваясь в [Uniform], когда разброса нет:
         * типичный журнал с коротким текстом так и остаётся на быстром пути деления.
         */
        fun of(heights: FloatArray, baseHeight: Float): RowMetrics {
            if (heights.all { it == baseHeight }) return Uniform(heights.size, baseHeight)
            val offsets = FloatArray(heights.size + 1)
            for (i in heights.indices) offsets[i + 1] = offsets[i] + heights[i]
            return Variable(offsets)
        }
    }
}

private fun RowMetrics.lastRow(): Int = (rowCount - 1).coerceAtLeast(0)

/**
 * Строит геометрию матрицы для [model], попутно измеряя высоты строк LOD0.
 *
 * Замер идёт здесь, при смене модели, а НЕ в кадре пинча: текст меряется в базовом масштабе, и это
 * ровно те вызовы [CellTextCache.field], которые потом сделает рендерер — так что работа не лишняя,
 * а перенесённая вперёд, и в живом жесте перемера по-прежнему нет ни одного.
 *
 * Высота строки = максимум по её заполненным ячейкам и полям, но не ниже [ROW_MIN_H]; строка без
 * единой заполненной ячейки получает [ROW_MIN_H].
 */
internal fun buildMatrixGeometry(
    density: Density,
    model: GridModel,
    measurer: TextMeasurer,
    cache: CellTextCache,
    styles: MatrixTextStyles,
): MatrixGeometry {
    // Ширины подколонок нужны, чтобы мерить текст, но сами от высот строк не зависят: сперва строим
    // раскладку на ровной сетке ради X-размеров, потом — итоговую, с измеренными высотами.
    val flat = MatrixGeometry(density, model.fields, model.contractors.size, RowMetrics.uniform(density, model.rows.size))
    return MatrixGeometry(density, model.fields, model.contractors.size, measureRows(model, flat, measurer, cache, styles))
}

private fun measureRows(
    model: GridModel,
    geometry: MatrixGeometry,
    measurer: TextMeasurer,
    cache: CellTextCache,
    styles: MatrixTextStyles,
): RowMetrics {
    val pad = geometry.cellPad
    val base = geometry.rowH
    val textWidths = IntArray(geometry.fieldCount) { (geometry.fieldWidth(it) - 2 * pad).roundToInt() }
    val heights = FloatArray(model.rows.size) { base }

    for (r in model.rows.indices) {
        var tallest = 0f
        for (cell in model.rows[r].cells) {
            if (cell == null) continue
            for (i in 0 until geometry.fieldCount) {
                val text = cell.values.getOrNull(i) ?: continue
                if (text.isEmpty()) continue
                val layout = cache.field(
                    measurer = measurer,
                    fieldIndex = i,
                    recordId = cell.recordId,
                    text = text,
                    style = styles.cell(i),
                    widthPx = textWidths[i],
                    maxLines = model.fields[i].lineCap(),
                )
                tallest = max(tallest, layout.size.height.toFloat())
            }
        }
        if (tallest > 0f) heights[r] = max(base, pad + tallest + pad)
    }
    return RowMetrics.of(heights, base)
}
