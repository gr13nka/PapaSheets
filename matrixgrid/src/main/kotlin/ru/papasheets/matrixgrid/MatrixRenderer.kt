package ru.papasheets.matrixgrid

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

private const val LABEL_PHOTO = "Ф"
private const val LABEL_LOCATION = "Л"
private const val LABEL_WORK = "ВИД РАБОТ"

/**
 * Рисует кадр матрицы. Экземпляр держит неизменные за кадр зависимости (геометрия, цвета, типографика,
 * измеритель, кэш), поэтому методы отрисовки короткие. Пересоздаётся только при смене геометрии/темы.
 *
 * Порядок отрисовки — 4 квадранта через clipRect: тело → закреплённая колонка дат (едет по panY) →
 * закреплённая шапка (едет по panX) → неподвижный угол. Тело рисует лишь видимый диапазон строк×групп.
 * В горячем пути нет аллокаций List (никаких map), а Offset/Size/IntOffset/IntSize — inline value-классы.
 */
internal class MatrixRenderer(
    private val geometry: MatrixGeometry,
    private val colors: MatrixColors,
    private val styles: MatrixTextStyles,
    private val measurer: TextMeasurer,
    private val cache: CellTextCache,
) {
    fun DrawScope.render(model: GridModel, state: MatrixState, thumbnails: ThumbnailSource) {
        cache.sync(model)
        thumbnails.version.value // snapshot-подписка: декодированный битмап инвалидирует draw

        val viewportW = size.width
        val viewportH = size.height
        state.maxPanX = geometry.maxPanX(viewportW)
        state.maxPanY = geometry.maxPanY(viewportH)
        val panX = state.panX.coerceIn(0f, state.maxPanX)
        val panY = state.panY.coerceIn(0f, state.maxPanY)
        val bodyW = geometry.bodyWidth(viewportW)
        val bodyH = geometry.bodyHeight(viewportH)

        drawRect(colors.background)

        clipRect(geometry.dateColW, geometry.headerH, viewportW, viewportH) {
            drawBody(model, panX, panY, bodyW, bodyH, thumbnails)
        }
        clipRect(0f, geometry.headerH, geometry.dateColW, viewportH) {
            drawDateColumn(model, panY, bodyH)
        }
        clipRect(geometry.dateColW, 0f, viewportW, geometry.headerH) {
            drawHeader(model, panX, bodyW)
        }
        clipRect(0f, 0f, geometry.dateColW, geometry.headerH) {
            drawCorner()
        }
    }

    private fun DrawScope.drawBody(
        model: GridModel,
        panX: Float,
        panY: Float,
        bodyW: Float,
        bodyH: Float,
        thumbnails: ThumbnailSource,
    ) {
        if (geometry.rowCount == 0 || geometry.groupCount == 0) return
        val row0 = geometry.firstVisibleRow(panY)
        val row1 = geometry.lastVisibleRow(panY, bodyH)
        val g0 = geometry.firstVisibleGroup(panX)
        val g1 = geometry.lastVisibleGroup(panX, bodyW)
        val thin = 1.dp.toPx()
        val thick = 2.dp.toPx()

        for (r in row0..row1) {
            val row = model.rows[r]
            val top = geometry.rowScreenTop(r, panY)
            for (g in g0..g1) {
                val left = geometry.groupScreenLeft(g, panX)
                val colorIndex = model.contractors[g].colorIndex
                drawRect(
                    color = colors.contractor(colorIndex).copy(alpha = colors.cellTintAlpha),
                    topLeft = Offset(left, top),
                    size = Size(geometry.groupW, geometry.rowH),
                )
                row.cells[g]?.let { cell -> drawFilledCell(cell, left, top, colorIndex, thumbnails) }
                drawLine(
                    color = colors.gridLine,
                    start = Offset(left + geometry.groupW, top),
                    end = Offset(left + geometry.groupW, top + geometry.rowH),
                    strokeWidth = thin,
                )
            }
            drawLine(
                color = if (row.isFirstOfDay) colors.dayDivider else colors.gridLine,
                start = Offset(0f, top),
                end = Offset(size.width, top),
                strokeWidth = if (row.isFirstOfDay) thick else thin,
            )
        }
    }

    private fun DrawScope.drawFilledCell(
        cell: GridCell,
        left: Float,
        top: Float,
        colorIndex: Int,
        thumbnails: ThumbnailSource,
    ) {
        val boxLeft = left + geometry.photoPadX
        val boxTop = top + geometry.photoPadY
        val box = geometry.photoBoxPx
        val key = cell.thumbKey
        when {
            key == null -> drawRect(
                color = colors.contractor(colorIndex).copy(alpha = colors.emptyPhotoAlpha),
                topLeft = Offset(boxLeft, boxTop),
                size = Size(box, box),
            )
            else -> {
                val bitmap = thumbnails.peek(key)
                if (bitmap != null) {
                    drawThumb(bitmap, boxLeft, boxTop, box)
                } else {
                    drawRect(
                        color = colors.contractor(colorIndex).copy(alpha = colors.placeholderAlpha),
                        topLeft = Offset(boxLeft, boxTop),
                        size = Size(box, box),
                    )
                    thumbnails.request(key, box.roundToInt())
                }
            }
        }

        val text = cache.cell(
            measurer = measurer,
            recordId = cell.recordId,
            workText = cell.workText,
            locationText = cell.locationCode,
            workStyle = styles.work,
            locationStyle = styles.location,
            workWidthPx = (geometry.workColW - 2 * geometry.cellPad).roundToInt(),
            locationWidthPx = (geometry.locColW - 2 * geometry.cellPad).roundToInt(),
        )
        val contentTop = top + geometry.cellPad
        drawText(
            textLayoutResult = text.location,
            color = colors.primaryText,
            topLeft = Offset(left + geometry.photoColW + geometry.cellPad, contentTop),
        )
        drawText(
            textLayoutResult = text.work,
            color = colors.secondaryText,
            topLeft = Offset(left + geometry.photoColW + geometry.locColW + geometry.cellPad, contentTop),
        )
    }

    private fun DrawScope.drawThumb(image: ImageBitmap, boxLeft: Float, boxTop: Float, box: Float) {
        val iw = image.width.toFloat()
        val ih = image.height.toFloat()
        val scale = min(box / iw, box / ih)
        val w = iw * scale
        val h = ih * scale
        drawImage(
            image = image,
            dstOffset = IntOffset((boxLeft + (box - w) / 2f).roundToInt(), (boxTop + (box - h) / 2f).roundToInt()),
            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
        )
    }

    private fun DrawScope.drawDateColumn(model: GridModel, panY: Float, bodyH: Float) {
        drawRect(
            color = colors.stickyBackground,
            topLeft = Offset(0f, geometry.headerH),
            size = Size(geometry.dateColW, size.height - geometry.headerH),
        )
        if (geometry.rowCount == 0) return
        val row0 = geometry.firstVisibleRow(panY)
        val row1 = geometry.lastVisibleRow(panY, bodyH)
        val labelWidth = (geometry.dateColW - 2 * geometry.cellPad).roundToInt()
        for (r in row0..row1) {
            val row = model.rows[r]
            if (!row.isFirstOfDay) continue
            val top = geometry.rowScreenTop(r, panY)
            val layout = cache.static(measurer, row.dayLabel, row.dayLabel, styles.dayLabel, labelWidth, 1)
            drawText(layout, color = colors.headerText, topLeft = Offset(geometry.cellPad, top + geometry.cellPad))
            drawLine(
                color = colors.dayDivider,
                start = Offset(0f, top),
                end = Offset(geometry.dateColW, top),
                strokeWidth = 2.dp.toPx(),
            )
        }
        drawLine(
            color = colors.gridLine,
            start = Offset(geometry.dateColW, geometry.headerH),
            end = Offset(geometry.dateColW, size.height),
            strokeWidth = 1.dp.toPx(),
        )
    }

    private fun DrawScope.drawHeader(model: GridModel, panX: Float, bodyW: Float) {
        drawRect(
            color = colors.headerBackground,
            topLeft = Offset(geometry.dateColW, 0f),
            size = Size(size.width - geometry.dateColW, geometry.headerH),
        )
        if (geometry.groupCount == 0) return
        val g0 = geometry.firstVisibleGroup(panX)
        val g1 = geometry.lastVisibleGroup(panX, bodyW)
        val thin = 1.dp.toPx()
        val nameWidth = (geometry.groupW - 2 * geometry.cellPad).roundToInt()
        for (g in g0..g1) {
            val left = geometry.groupScreenLeft(g, panX)
            val contractor = model.contractors[g]
            val name = cache.static(measurer, contractor.id, contractor.name, styles.contractorName, nameWidth, 1)
            drawText(
                textLayoutResult = name,
                color = colors.headerText,
                topLeft = Offset(left + geometry.cellPad, (geometry.nameRowH - name.size.height) / 2f),
            )
            drawSubHeader(LABEL_PHOTO, left, geometry.photoColW)
            drawSubHeader(LABEL_LOCATION, left + geometry.photoColW, geometry.locColW)
            drawSubHeader(LABEL_WORK, left + geometry.photoColW + geometry.locColW, geometry.workColW)
            drawLine(
                color = colors.gridLine,
                start = Offset(left + geometry.groupW, 0f),
                end = Offset(left + geometry.groupW, geometry.headerH),
                strokeWidth = thin,
            )
        }
        drawLine(
            color = colors.gridLine,
            start = Offset(geometry.dateColW, geometry.nameRowH),
            end = Offset(size.width, geometry.nameRowH),
            strokeWidth = thin,
        )
        drawLine(
            color = colors.dayDivider,
            start = Offset(geometry.dateColW, geometry.headerH),
            end = Offset(size.width, geometry.headerH),
            strokeWidth = thin,
        )
    }

    private fun DrawScope.drawSubHeader(label: String, subLeft: Float, subWidth: Float) {
        val layout = cache.static(measurer, label, label, styles.subHeader, (subWidth - 2 * geometry.cellPad).roundToInt(), 1)
        drawText(
            textLayoutResult = layout,
            color = colors.secondaryText,
            topLeft = Offset(
                subLeft + (subWidth - layout.size.width) / 2f,
                geometry.nameRowH + (geometry.subHeaderH - layout.size.height) / 2f,
            ),
        )
    }

    private fun DrawScope.drawCorner() {
        drawRect(color = colors.headerBackground, topLeft = Offset.Zero, size = Size(geometry.dateColW, geometry.headerH))
        drawLine(
            color = colors.gridLine,
            start = Offset(geometry.dateColW, 0f),
            end = Offset(geometry.dateColW, geometry.headerH),
            strokeWidth = 1.dp.toPx(),
        )
        drawLine(
            color = colors.dayDivider,
            start = Offset(0f, geometry.headerH),
            end = Offset(geometry.dateColW, geometry.headerH),
            strokeWidth = 1.dp.toPx(),
        )
    }
}
