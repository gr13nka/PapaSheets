package ru.papasheets.matrixgrid

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

private const val LABEL_PHOTO = "Ф"

/**
 * Ключ фото-подколонки в кэше статических подписей. Подписи полей идут под ключами `"h:" + id`:
 * ключом служит идентификатор поля, а не его текст, потому что заголовки задаёт пользователь и два
 * поля вполне могут называться одинаково, а [CellTextCache.static] — общая карта на всю матрицу.
 */
private const val KEY_PHOTO_HEADER = "h:photo"

/**
 * Рисует кадр матрицы на текущем зуме. Экземпляр держит неизменные за кадр зависимости (геометрия,
 * цвета, типографика, измеритель, кэш); пересоздаётся только при смене геометрии/темы.
 *
 * Зум здесь не «слой поверх готовой картинки», а полноценный перерендер каждый кадр (см. отчёт M4):
 * стики-шапки собраны из 4 квадрантов через clipRect и на живом пинче остаются закреплёнными и
 * читаемыми — равномерный graphicsLayer-скейл этого бы не дал. Дорогое (layout текста) берётся из
 * [cache] в базовом масштабе, а промежуточный зум добирается canvas-трансформом [scale] вокруг
 * левого-верхнего угла ячейки/подписи — ни одного TextMeasurer.measure и ни одного decode в кадре
 * пинча. [Lod] по зуму решает, что вообще рисовать: LOD2 — только сплошные блоки (без битмапов и
 * текста), LOD1 — микро-превью и код локации, LOD0 — полная ячейка.
 *
 * pan/zoom читаются из [MatrixState] (snapshot-float) прямо здесь, в draw — их смена перезапускает
 * draw, но не рекомпозицию. Границы pan и диапазон зума не хранятся в состоянии: их на месте считает
 * [MatrixGeometry] (единый источник и для жестов).
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
        // Публикуем контекст клампинга кадра (обычные поля — без инвалидации draw). Чтение ниже
        // всё равно клампит защитно: между записью жеста и этим кадром вьюпорт мог смениться (поворот).
        state.updateViewport(geometry, viewportW, viewportH)
        val zoom = geometry.clampZoom(state.zoom, viewportW, viewportH)
        val panX = geometry.clampPanX(state.panX, viewportW, zoom)
        val panY = geometry.clampPanY(state.panY, viewportH, zoom)
        val lod = Lod.forZoom(zoom)

        val dateColW = geometry.dateColW(zoom)
        val headerH = geometry.headerH(zoom)
        val bodyW = (viewportW - dateColW).coerceAtLeast(0f)
        val bodyH = (viewportH - headerH).coerceAtLeast(0f)

        drawRect(colors.background)

        clipRect(dateColW, headerH, viewportW, viewportH) {
            drawBody(model, panX, panY, bodyW, bodyH, zoom, lod, thumbnails)
        }
        clipRect(0f, headerH, dateColW, viewportH) {
            drawDateColumn(model, panY, bodyH, zoom, lod, dateColW, headerH)
        }
        clipRect(dateColW, 0f, viewportW, headerH) {
            drawHeader(model, panX, bodyW, zoom, lod, dateColW, headerH)
        }
        clipRect(0f, 0f, dateColW, headerH) {
            drawCorner(dateColW, headerH)
        }
    }

    private fun DrawScope.drawBody(
        model: GridModel,
        panX: Float,
        panY: Float,
        bodyW: Float,
        bodyH: Float,
        zoom: Float,
        lod: Lod,
        thumbnails: ThumbnailSource,
    ) {
        if (geometry.rowCount == 0 || geometry.groupCount == 0) return
        val row0 = geometry.firstVisibleRow(panY, zoom)
        val row1 = geometry.lastVisibleRow(panY, bodyH, zoom)
        val g0 = geometry.firstVisibleGroup(panX, zoom)
        val g1 = geometry.lastVisibleGroup(panX, bodyW, zoom)
        val cellW = geometry.groupW * zoom
        val thin = 1.dp.toPx()
        val thick = 2.dp.toPx()

        for (r in row0..row1) {
            val row = model.rows[r]
            val top = geometry.rowScreenTop(r, panY, zoom)
            // Высота своя у каждой строки: на LOD0 строка растянута под самый длинный текст в ней.
            val cellH = geometry.rowHeight(r, zoom) * zoom
            for (g in g0..g1) {
                val left = geometry.groupScreenLeft(g, panX, zoom)
                val colorIndex = model.contractors[g].colorIndex
                val cell = row.cells[g]
                if (lod == Lod.LOD2) {
                    // «Картина месяца»: заполненная ячейка — сплошной блок цвета подрядчика. Без линий,
                    // тонировки, битмапов и текста — иначе тысячи видимых ячеек не уложатся в кадр.
                    if (cell != null) {
                        drawRect(colors.contractor(colorIndex), Offset(left, top), Size(cellW, cellH))
                    }
                } else {
                    drawRect(
                        color = colors.contractor(colorIndex).copy(alpha = colors.cellTintAlpha),
                        topLeft = Offset(left, top),
                        size = Size(cellW, cellH),
                    )
                    if (cell != null) {
                        drawFilledCell(model.fields, cell, left, top, geometry.rowHeight(r, zoom), colorIndex, zoom, lod, thumbnails)
                    }
                    drawLine(
                        color = colors.gridLine,
                        start = Offset(left + cellW, top),
                        end = Offset(left + cellW, top + cellH),
                        strokeWidth = thin,
                    )
                }
            }
            when {
                lod != Lod.LOD2 -> drawLine(
                    color = if (row.isFirstOfDay) colors.dayDivider else colors.gridLine,
                    start = Offset(0f, top),
                    end = Offset(size.width, top),
                    strokeWidth = if (row.isFirstOfDay) thick else thin,
                )
                row.isFirstOfDay -> drawLine(
                    color = colors.dayDivider,
                    start = Offset(0f, top),
                    end = Offset(size.width, top),
                    strokeWidth = thin,
                )
            }
        }
    }

    /**
     * Содержимое заполненной ячейки рисуется в базовых (zoom = 1) координатах внутри
     * translate+scale, поэтому измеренный в кэше текст и битмап масштабируются canvas'ом, без перемера.
     *
     * Фото и текст выровнены по ВЕРХУ ячейки (общий отступ [MatrixGeometry.cellPad]). При базовой
     * высоте строки это ровно прежнее вертикальное центрирование плитки, а в выросшей под длинный
     * текст строке фото остаётся у своей подписи, а не уползает в середину пустоты.
     *
     * Содержимое подрезается по [rowHeight] — высоте строки на ТЕКУЩЕМ ярусе. Замер текста от яруса
     * не зависит (так устроен [CellTextCache]: один результат на ячейку, ключ — recordId), и на LOD0
     * строка сама растянута под этот замер, так что подрезка там ничего не отсекает. А на LOD1/LOD2
     * строка фиксирована базовой высотой, и без подрезки многострочный текст рисовался бы во всю
     * измеренную высоту — поверх строк ниже. Ограничение общее, а не ветка по ярусу: содержимое
     * ячейки не выходит за свою строку никогда, и проверять это отдельно для каждого яруса не нужно.
     */
    private fun DrawScope.drawFilledCell(
        fields: List<GridField>,
        cell: GridCell,
        left: Float,
        top: Float,
        rowHeight: Float,
        colorIndex: Int,
        zoom: Float,
        lod: Lod,
        thumbnails: ThumbnailSource,
    ) {
        translate(left = left, top = top) {
            scale(scale = zoom, pivot = Offset.Zero) {
                clipRect(left = 0f, top = 0f, right = geometry.groupW, bottom = rowHeight) {
                    val keys = cell.thumbKeys
                    if (keys.isEmpty()) {
                        // Пустая Ф-подколонка: один блок «фото ещё нет» на весь квадрат.
                        val tile = geometry.photoTiles(1).single()
                        drawRect(
                            color = colors.contractor(colorIndex).copy(alpha = colors.emptyPhotoAlpha),
                            topLeft = Offset(tile.left, tile.top),
                            size = Size(tile.size, tile.size),
                        )
                    } else {
                        val tiles = geometry.photoTiles(keys.size)
                        keys.take(tiles.size).forEachIndexed { slot, key ->
                            val tile = tiles[slot]
                            val bitmap = thumbnails.peek(key)
                            if (bitmap != null) {
                                drawThumb(bitmap, tile.left, tile.top, tile.size)
                            } else {
                                drawRect(
                                    color = colors.contractor(colorIndex).copy(alpha = colors.placeholderAlpha),
                                    topLeft = Offset(tile.left, tile.top),
                                    size = Size(tile.size, tile.size),
                                )
                                thumbnails.request(key, (tile.size * zoom).roundToInt())
                            }
                        }
                    }

                    // Заливка подколонки цветом её значения — до текста, чтобы он лёг поверх.
                    //
                    // Рисуется на обоих ярусах и для ВСЕХ полей, включая скрытые на LOD1
                    // (`showAtCompactLod = false`, как у «Вида работ»): этот флаг сдерживает замер и
                    // отрисовку текста — то, что дорого, — а не саму подколонку. Один drawRect
                    // ничего не стоит, зато именно на LOD1, где текст уже не прочесть, цвет и
                    // остаётся единственным способом различить виды работ.
                    for (i in fields.indices) {
                        val colorIndex = cell.valueColors.getOrNull(i) ?: continue
                        drawRect(
                            color = colors.value(colorIndex).copy(alpha = colors.valueFillAlpha),
                            topLeft = Offset(geometry.fieldLeft(i), 0f),
                            size = Size(geometry.fieldWidth(i), rowHeight),
                        )
                    }

                    val pad = geometry.cellPad
                    for (i in fields.indices) {
                        val field = fields[i]
                        if (lod == Lod.LOD1 && !field.showAtCompactLod) continue
                        val text = cell.values.getOrNull(i) ?: continue
                        if (text.isEmpty()) continue
                        val layout = cache.field(
                            measurer = measurer,
                            fieldIndex = i,
                            recordId = cell.recordId,
                            text = text,
                            style = styles.cell(i),
                            widthPx = (geometry.fieldWidth(i) - 2 * pad).roundToInt(),
                            maxLines = field.lineCap(),
                        )
                        drawText(
                            textLayoutResult = layout,
                            color = if (i == 0) colors.primaryText else colors.secondaryText,
                            topLeft = Offset(geometry.fieldLeft(i) + pad, pad),
                        )
                    }
                }
            }
        }
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

    private fun DrawScope.drawDateColumn(
        model: GridModel,
        panY: Float,
        bodyH: Float,
        zoom: Float,
        lod: Lod,
        dateColW: Float,
        headerH: Float,
    ) {
        drawRect(
            color = colors.stickyBackground,
            topLeft = Offset(0f, headerH),
            size = Size(dateColW, size.height - headerH),
        )
        if (geometry.rowCount == 0) return
        val ds = geometry.dateColScale(zoom)
        val row0 = geometry.firstVisibleRow(panY, zoom)
        val row1 = geometry.lastVisibleRow(panY, bodyH, zoom)
        val labelWidth = (geometry.dateColWBase - 2 * geometry.cellPad).roundToInt()
        for (r in row0..row1) {
            val row = model.rows[r]
            if (!row.isFirstOfDay) continue
            val top = geometry.rowScreenTop(r, panY, zoom)
            // На LOD2 колонка дат показывает только число дня — так подписи не сливаются на сжатых строках.
            val text = if (lod == Lod.LOD2) row.dayNumber else row.dayLabel
            val layout = cache.static(measurer, text, text, styles.dayLabel, labelWidth, 1)
            translate(left = 0f, top = top) {
                scale(scale = ds, pivot = Offset.Zero) {
                    drawText(layout, color = colors.headerText, topLeft = Offset(geometry.cellPad, geometry.cellPad))
                }
            }
            drawLine(
                color = colors.dayDivider,
                start = Offset(0f, top),
                end = Offset(dateColW, top),
                strokeWidth = 2.dp.toPx(),
            )
        }
        drawLine(
            color = colors.gridLine,
            start = Offset(dateColW, headerH),
            end = Offset(dateColW, size.height),
            strokeWidth = 1.dp.toPx(),
        )
    }

    private fun DrawScope.drawHeader(
        model: GridModel,
        panX: Float,
        bodyW: Float,
        zoom: Float,
        lod: Lod,
        dateColW: Float,
        headerH: Float,
    ) {
        drawRect(
            color = colors.headerBackground,
            topLeft = Offset(dateColW, 0f),
            size = Size(size.width - dateColW, headerH),
        )
        if (geometry.groupCount == 0) return
        val hs = geometry.headerScale(zoom)
        val nameRowH = geometry.nameRowH(zoom)
        val subHeaderH = geometry.subHeaderH(zoom)
        val g0 = geometry.firstVisibleGroup(panX, zoom)
        val g1 = geometry.lastVisibleGroup(panX, bodyW, zoom)
        val cellW = geometry.groupW * zoom
        val thin = 1.dp.toPx()
        val nameWidth = (geometry.groupW - 2 * geometry.cellPad).roundToInt()

        for (g in g0..g1) {
            val left = geometry.groupScreenLeft(g, panX, zoom)
            val contractor = model.contractors[g]
            // Каждая шапка группы клипится своей колонкой: имя/подписи не должны затекать в соседа,
            // когда на слабом зуме кегль клампится, а колонка узкая.
            // Архивный подрядчик остался колонкой только из-за записей в этом журнале — имя приглушено
            // (secondaryText вместо headerText), чтобы шапка отличала его от активных без лишних меток.
            val nameColor = if (contractor.isArchived) colors.secondaryText else colors.headerText
            clipRect(left, 0f, left + cellW, headerH) {
                if (lod == Lod.LOD2) {
                    // Колонка подрядчика на LOD2 — тонкая полоска (~13dp): горизонтальный shortName
                    // наезжал бы на соседей. Рисуем вертикально (снизу вверх) — влезает в свою колонку.
                    val short = cache.static(measurer, "s:" + contractor.id, contractor.shortName, styles.contractorName, nameWidth, 1)
                    drawVerticalLabel(short, nameColor, hs, left, cellW, headerH)
                } else {
                    val name = cache.static(measurer, contractor.id, contractor.name, styles.contractorName, nameWidth, 1)
                    drawScaledLabel(name, nameColor, hs, left + geometry.cellPad * hs, cellW, 0f, nameRowH, center = false)
                    drawSubHeader(KEY_PHOTO_HEADER, LABEL_PHOTO, left, geometry.photoColW, nameRowH, subHeaderH, hs, zoom)
                    for (i in model.fields.indices) {
                        val field = model.fields[i]
                        drawSubHeader(
                            key = "h:" + field.id,
                            label = field.title,
                            subLeft = left + geometry.fieldLeft(i) * zoom,
                            baseSubWidth = geometry.fieldWidth(i),
                            nameRowH = nameRowH,
                            subHeaderH = subHeaderH,
                            hs = hs,
                            zoom = zoom,
                        )
                    }
                }
            }
            if (lod != Lod.LOD2) {
                drawLine(
                    color = colors.gridLine,
                    start = Offset(left + cellW, 0f),
                    end = Offset(left + cellW, headerH),
                    strokeWidth = thin,
                )
            }
        }
        if (lod != Lod.LOD2) {
            drawLine(color = colors.gridLine, start = Offset(dateColW, nameRowH), end = Offset(size.width, nameRowH), strokeWidth = thin)
        }
        drawLine(color = colors.dayDivider, start = Offset(dateColW, headerH), end = Offset(size.width, headerH), strokeWidth = thin)
    }

    private fun DrawScope.drawSubHeader(
        key: String,
        label: String,
        subLeft: Float,
        baseSubWidth: Float,
        nameRowH: Float,
        subHeaderH: Float,
        hs: Float,
        zoom: Float,
    ) {
        val layout = cache.static(measurer, key, label, styles.subHeader, (baseSubWidth - 2 * geometry.cellPad).roundToInt(), 1)
        drawScaledLabel(layout, colors.secondaryText, hs, subLeft, baseSubWidth * zoom, nameRowH, subHeaderH, center = true)
    }

    /**
     * Рисует заранее измеренную подпись, масштабируя её кегль на [scaleFactor] (клампленный масштаб
     * шапки) и размещая в экранной полосе [boxLeft]..[boxLeft]+[boxWidth] × [bandTop]..[bandTop]+[bandHeight].
     * [center] — по центру полосы (shortName, подписи Ф/Л/ВИД), иначе от левого края (полное имя).
     */
    private fun DrawScope.drawScaledLabel(
        layout: TextLayoutResult,
        color: Color,
        scaleFactor: Float,
        boxLeft: Float,
        boxWidth: Float,
        bandTop: Float,
        bandHeight: Float,
        center: Boolean,
    ) {
        val w = layout.size.width * scaleFactor
        val h = layout.size.height * scaleFactor
        val x = if (center) boxLeft + (boxWidth - w) / 2f else boxLeft
        val y = bandTop + (bandHeight - h) / 2f
        translate(left = x, top = y) {
            scale(scale = scaleFactor, pivot = Offset.Zero) {
                drawText(layout, color = color, topLeft = Offset.Zero)
            }
        }
    }

    /**
     * Рисует подпись повёрнутой на 90° (читается снизу вверх), по центру узкой колонки
     * [boxLeft]..[boxLeft]+[boxWidth] в полосе высотой [bandHeight]. Нужна для shortName подрядчиков на
     * LOD2, где колонка тоньше горизонтальной подписи. Кегль масштабируется на [scaleFactor], как в
     * [drawScaledLabel]; поворот вокруг центра колонки, текст центрируется в (cx, cy).
     */
    private fun DrawScope.drawVerticalLabel(
        layout: TextLayoutResult,
        color: Color,
        scaleFactor: Float,
        boxLeft: Float,
        boxWidth: Float,
        bandHeight: Float,
    ) {
        val cx = boxLeft + boxWidth / 2f
        val cy = bandHeight / 2f
        val w = layout.size.width * scaleFactor
        val h = layout.size.height * scaleFactor
        rotate(degrees = -90f, pivot = Offset(cx, cy)) {
            translate(left = cx - w / 2f, top = cy - h / 2f) {
                scale(scale = scaleFactor, pivot = Offset.Zero) {
                    drawText(layout, color = color, topLeft = Offset.Zero)
                }
            }
        }
    }

    private fun DrawScope.drawCorner(dateColW: Float, headerH: Float) {
        drawRect(color = colors.headerBackground, topLeft = Offset.Zero, size = Size(dateColW, headerH))
        drawLine(
            color = colors.gridLine,
            start = Offset(dateColW, 0f),
            end = Offset(dateColW, headerH),
            strokeWidth = 1.dp.toPx(),
        )
        drawLine(
            color = colors.dayDivider,
            start = Offset(0f, headerH),
            end = Offset(dateColW, headerH),
            strokeWidth = 1.dp.toPx(),
        )
    }
}
