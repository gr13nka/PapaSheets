package ru.papasheets.matrixgrid

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer

/**
 * Единственный публичный вход движка матрицы: один Canvas-рендерер (не дерево composable'ов на ячейку).
 * Скрывает виртуализацию, стики-шапки, кэш текста, hit-тест и жесты (pan, fling, пинч-зум, double-tap,
 * тапы/лонг-прессы). Рекомпозиция происходит только при смене иммутабельной [model]; прокрутка, зум и
 * инерция живут в [state] и трогают лишь draw-слой.
 *
 * @param model  готовая раскладка «дата × подрядчик» (строит app/GridModelBuilder).
 * @param state  состояние прокрутки/зума; создаётся через [rememberMatrixState].
 * @param thumbnails источник превью ячеек; его [ThumbnailSource.version] дёргает перерисовку.
 * @param callbacks доменные события тапов/лонг-прессов по ячейкам.
 */
@Composable
fun MatrixView(
    model: GridModel,
    state: MatrixState,
    thumbnails: ThumbnailSource,
    callbacks: MatrixCallbacks,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val dark = isSystemInDarkTheme()
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()

    val colors = remember(dark) { MatrixColors.of(dark) }
    val styles = remember { MatrixTextStyles() }
    // Кэш переживает смену модели (сам её отслеживает), но не смену плотности: замеры сделаны в
    // пикселях, и после смены масштаба шрифта/экрана они уже не про эту раскладку.
    val cache = remember(density) { CellTextCache() }
    // Ключ — сама модель, а не её размеры: правка ширины поля или текста ячейки меняет раскладку, не
    // меняя ни числа строк, ни числа колонок, и по размерам была бы не видна.
    val geometry = remember(density, model, styles) {
        cache.sync(model)
        buildMatrixGeometry(density, model, measurer, cache, styles)
    }
    val renderer = remember(geometry, colors, styles) {
        MatrixRenderer(geometry, colors, styles, measurer, cache)
    }

    Spacer(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .matrixTransformGestures(state, geometry, scope)
            .pointerInput(model, geometry) {
                detectTapGestures(
                    onTap = { offset ->
                        dispatchHit(model, geometry, state, callbacks, offset, longPress = false, size.width.toFloat(), size.height.toFloat())
                    },
                    onLongPress = { offset ->
                        dispatchHit(model, geometry, state, callbacks, offset, longPress = true, size.width.toFloat(), size.height.toFloat())
                    },
                    onDoubleTap = { offset -> state.toggleOverviewAt(scope, offset.x, offset.y) },
                )
            }
            .drawBehind {
                with(renderer) { render(model, state, thumbnails) }
            },
    )
}

/** Переводит попадание пальца в геометрическую ячейку доменного колбэка (или молча игнорирует). */
private fun dispatchHit(
    model: GridModel,
    geometry: MatrixGeometry,
    state: MatrixState,
    callbacks: MatrixCallbacks,
    offset: Offset,
    longPress: Boolean,
    viewportW: Float,
    viewportH: Float,
) {
    val zoom = geometry.clampZoom(state.zoom, viewportW, viewportH)
    val panX = geometry.clampPanX(state.panX, viewportW, zoom)
    val panY = geometry.clampPanY(state.panY, viewportH, zoom)
    val hit = geometry.hitTest(offset.x, offset.y, panX, panY, zoom)
    if (hit !is MatrixHit.Body) return
    val row = model.rows.getOrNull(hit.row) ?: return
    val contractor = model.contractors.getOrNull(hit.group) ?: return
    val cell = row.cells.getOrNull(hit.group)
    if (cell == null) {
        if (!longPress) callbacks.onEmptySlotTap(row.dateEpochDay, contractor.id)
        return
    }
    when {
        longPress -> callbacks.onCellLongPress(cell.recordId)
        // Слот из хит-теста — половина Ф-подколонки; зажимаем по реальному числу фото, чтобы тап по
        // пустой правой половине записи с одним фото открыл это единственное фото, а не слот 1.
        hit.onPhoto && cell.thumbKeys.isNotEmpty() ->
            callbacks.onPhotoTap(cell.recordId, hit.photoSlot.coerceAtMost(cell.thumbKeys.size - 1))
        else -> callbacks.onCellTap(cell.recordId)
    }
}
