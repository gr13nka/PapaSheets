package ru.papasheets.matrixgrid

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Одно-пальцевый pan с инерцией. Своя реализация на [awaitEachGesture] (а не detectDragGestures) —
 * ради скорости через [VelocityTracker] и корректного прерывания инерции новым касанием. Порог
 * touchSlop проверяется вручную: пока палец не сдвинулся дальше него, жест может оказаться тапом и
 * отдаётся detectTapGestures; после пересечения — движение потребляется и начинается pan.
 *
 * Инерция ([splineBasedDecay] по каждой оси) запускается в [flingScope] и хранится в локальном
 * [Job]; следующий палец её отменяет. Пан пишется только в [MatrixState] (snapshot-float) —
 * рекомпозиции не происходит, перерисовывается лишь draw-слой.
 *
 * Пинч сюда не добавлен (M4), но детектор для него открыт: в том же [awaitEachGesture] достаточно
 * начать отслеживать второй указатель и ветвиться на масштабирование вместо pan.
 */
internal fun Modifier.matrixPanGestures(state: MatrixState, flingScope: CoroutineScope): Modifier =
    pointerInput(Unit) {
        val decay = splineBasedDecay<Float>(this)
        val touchSlop = viewConfiguration.touchSlop
        val velocityTracker = VelocityTracker()
        var flingJob: Job? = null

        awaitEachGesture {
            flingJob?.cancel()
            val down = awaitFirstDown(requireUnconsumed = false)
            velocityTracker.resetTracking()
            velocityTracker.addPointerInputChange(down)

            // Копим движение до пересечения slop; до него — не наш жест (возможен тап).
            var dragging = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.fastFirst { it.id == down.id } ?: break
                if (!change.pressed) break // палец отпущен до slop — это tap
                velocityTracker.addPointerInputChange(change)
                val total = change.position - down.position
                if (total.getDistance() >= touchSlop) {
                    dragging = true
                    state.panBy(total.x, total.y)
                    change.consume()
                    break
                }
            }
            if (!dragging) return@awaitEachGesture

            val liftedCleanly = drag(down.id) { change ->
                velocityTracker.addPointerInputChange(change)
                val delta = change.positionChange()
                state.panBy(delta.x, delta.y)
                change.consume()
            }
            if (!liftedCleanly) return@awaitEachGesture

            val velocity = velocityTracker.calculateVelocity()
            flingJob = flingScope.launch {
                coroutineScope {
                    launch {
                        AnimationState(initialValue = state.panX, initialVelocity = -velocity.x).animateDecay(decay) {
                            val target = value
                            state.applyPanX(target)
                            if (state.panX != target) cancelAnimation() // упёрлись в границу — гасим ось
                        }
                    }
                    launch {
                        AnimationState(initialValue = state.panY, initialVelocity = -velocity.y).animateDecay(decay) {
                            val target = value
                            state.applyPanY(target)
                            if (state.panY != target) cancelAnimation()
                        }
                    }
                }
            }
        }
    }

/** Первый элемент по предикату без аллокации итератора (в горячем цикле указателей). */
private inline fun <T> List<T>.fastFirst(predicate: (T) -> Boolean): T? {
    for (index in indices) {
        val item = this[index]
        if (predicate(item)) return item
    }
    return null
}
