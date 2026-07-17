package ru.papasheets.matrixgrid

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Фаза жеста: до порога (может оказаться тапом) → одно-пальцевый pan (с fling) → двух-пальцевый pinch. */
private enum class GestureMode { UNDECIDED, PAN, PINCH }

/**
 * Единый детектор трансформаций тела матрицы на [awaitEachGesture]: одно-пальцевый pan с инерцией и
 * двух-пальцевый pinch (зум вокруг центроида, совмещённый с pan). Своя реализация, а не
 * detectTransformGestures — ради [VelocityTracker]/fling на одном пальце и корректного прерывания
 * инерции новым касанием.
 *
 * До пересечения touchSlop жест «не наш» (событий не потребляет) — так одиночный/двойной тап
 * достаются детектору тапов в [MatrixView]. Как только видно ≥2 указателей, жест до конца считается
 * пинчем (fling по нему не запускается). Клампинг pan/zoom единый: все записи идут через
 * [MatrixState.setTransform]/[MatrixState.panBy], которые клампят по опубликованному контексту кадра;
 * пивот-математику держит [MatrixGeometry].
 *
 * [pointerInput] пере-ключается по [geometry]: смена модели даёт новую геометрию (и новый мир), а это
 * не кадр пинча — перезапуск детектора безопасен.
 */
internal fun Modifier.matrixTransformGestures(
    state: MatrixState,
    geometry: MatrixGeometry,
    scope: CoroutineScope,
): Modifier = pointerInput(geometry) {
    val decay = splineBasedDecay<Float>(this)
    val touchSlop = viewConfiguration.touchSlop
    val velocityTracker = VelocityTracker()

    awaitEachGesture {
        state.motionJob?.cancel()
        state.updateViewport(geometry, size.width.toFloat(), size.height.toFloat())

        val down = awaitFirstDown(requireUnconsumed = false)
        velocityTracker.resetTracking()
        velocityTracker.addPointerInputChange(down)

        var mode = GestureMode.UNDECIDED
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.pressedCount()
            if (pressed == 0) break

            if (pressed >= 2) {
                mode = GestureMode.PINCH
                applyPinch(state, geometry, event)
                event.consumePressed()
                continue
            }

            val change = event.firstPressed() ?: break
            when (mode) {
                GestureMode.PINCH -> {
                    // Второй палец поднят — доводим жест как pan остатком, без инерции.
                    val delta = change.positionChange()
                    state.panBy(delta.x, delta.y)
                    change.consume()
                }
                GestureMode.PAN -> {
                    velocityTracker.addPointerInputChange(change)
                    val delta = change.positionChange()
                    state.panBy(delta.x, delta.y)
                    change.consume()
                }
                GestureMode.UNDECIDED -> {
                    velocityTracker.addPointerInputChange(change)
                    val total = change.position - down.position
                    if (total.getDistance() >= touchSlop) {
                        mode = GestureMode.PAN
                        state.panBy(total.x, total.y)
                        change.consume()
                    }
                }
            }
        }

        if (mode == GestureMode.PAN) {
            val velocity = velocityTracker.calculateVelocity()
            state.motionJob = scope.launch {
                coroutineScope {
                    launch {
                        AnimationState(initialValue = state.panX, initialVelocity = -velocity.x).animateDecay(decay) {
                            val target = value
                            state.setTransform(state.zoom, target, state.panY)
                            if (state.panX != target) cancelAnimation() // упёрлись в границу — гасим ось
                        }
                    }
                    launch {
                        AnimationState(initialValue = state.panY, initialVelocity = -velocity.y).animateDecay(decay) {
                            val target = value
                            state.setTransform(state.zoom, state.panX, target)
                            if (state.panY != target) cancelAnimation()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Один шаг пинча: зум множится на отношение «размахов» пальцев вокруг центроида, а сам сдвиг центроида
 * даёт pan. Порядок: сперва меняем зум так, чтобы мировая точка под центроидом не уехала
 * ([MatrixGeometry.panXForZoom]), затем сдвигаем на перемещение центроида — контент следует за пальцами.
 * Кламп — в [MatrixState.setTransform]; [MatrixGeometry.clampZoom] здесь нужен пивот-математике.
 */
private fun applyPinch(state: MatrixState, geometry: MatrixGeometry, event: PointerEvent) {
    val current = event.centroid(useCurrent = true)
    val previous = event.centroid(useCurrent = false)
    val panDeltaX = current.x - previous.x
    val panDeltaY = current.y - previous.y
    val spanCurrent = event.span(current, useCurrent = true)
    val spanPrevious = event.span(previous, useCurrent = false)
    val zoomFactor = if (spanPrevious > 0.001f) spanCurrent / spanPrevious else 1f

    val zoomOld = state.zoom
    val zoomNew = state.clampedZoom(zoomOld * zoomFactor)
    val panX = geometry.panXForZoom(current.x, state.panX, zoomOld, zoomNew) - panDeltaX
    val panY = geometry.panYForZoom(current.y, state.panY, zoomOld, zoomNew) - panDeltaY
    state.setTransform(zoomNew, panX, panY)
}

// --- Разбор мультитач-события без аллокаций (горячий путь указателей). ---

private fun PointerEvent.pressedCount(): Int {
    var count = 0
    for (i in changes.indices) if (changes[i].pressed) count++
    return count
}

private fun PointerEvent.firstPressed(): PointerInputChange? {
    for (i in changes.indices) {
        val change = changes[i]
        if (change.pressed) return change
    }
    return null
}

private fun PointerEvent.consumePressed() {
    for (i in changes.indices) {
        val change = changes[i]
        if (change.pressed) change.consume()
    }
}

/** Средняя точка прижатых указателей (текущая или предыдущая позиция). Вызывать при ≥1 прижатом. */
private fun PointerEvent.centroid(useCurrent: Boolean): Offset {
    var sumX = 0f
    var sumY = 0f
    var count = 0
    for (i in changes.indices) {
        val change = changes[i]
        if (!change.pressed) continue
        val point = if (useCurrent) change.position else change.previousPosition
        sumX += point.x
        sumY += point.y
        count++
    }
    return if (count == 0) Offset.Zero else Offset(sumX / count, sumY / count)
}

/** Средний «размах» — расстояние прижатых указателей до центроида; отношение размахов = множитель зума. */
private fun PointerEvent.span(centroid: Offset, useCurrent: Boolean): Float {
    var sum = 0f
    var count = 0
    for (i in changes.indices) {
        val change = changes[i]
        if (!change.pressed) continue
        val point = if (useCurrent) change.position else change.previousPosition
        sum += (point - centroid).getDistance()
        count++
    }
    return if (count == 0) 0f else sum / count
}
