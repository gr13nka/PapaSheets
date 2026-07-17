package ru.papasheets.matrixgrid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember

/** Держатель состояния прокрутки/зума матрицы. Переживает рекомпозицию, пока модель на экране. */
@Composable
fun rememberMatrixState(): MatrixState = remember { MatrixState() }

/**
 * Изменяемое состояние вьюпорта матрицы. Ключевое правило проекта: [panX]/[panY]/[zoom] —
 * snapshot-float'ы, их читают ТОЛЬКО draw- и gesture-лямбды, никогда тело композиции —
 * иначе каждый кадр прокрутки вызывал бы рекомпозицию.
 *
 * Геометрические границы ([maxPanX]/[maxPanY]/[viewportW]/[viewportH]) — обычные поля: их
 * пишет рендерер по факту известного размера кадра, а читают жесты для клампинга и hit-теста.
 * Они не snapshot-состояние специально — их изменение не должно инвалидировать draw само по себе
 * (draw и так перезапустится из-за pan/размера), а лишняя подписка только добавила бы кадров.
 */
@Stable
class MatrixState {
    private val _panX = mutableFloatStateOf(0f)
    private val _panY = mutableFloatStateOf(0f)

    /** Смещение тела матрицы вправо от начала мира, px. Читать только в draw/gesture. */
    val panX: Float get() = _panX.floatValue

    /** Смещение тела матрицы вниз от начала мира, px. Читать только в draw/gesture. */
    val panY: Float get() = _panY.floatValue

    /** Масштаб. В M3 всегда 1f; поле заведено под пинч-зум M4, чтобы не менять контракт позже. */
    val zoom: Float get() = _zoom.floatValue
    private val _zoom = mutableFloatStateOf(1f)

    // Границы прокрутки, обновляемые рендерером каждый кадр (см. KDoc класса).
    internal var maxPanX: Float = 0f
    internal var maxPanY: Float = 0f

    internal fun applyPanX(value: Float) { _panX.floatValue = value.coerceIn(0f, maxPanX) }
    internal fun applyPanY(value: Float) { _panY.floatValue = value.coerceIn(0f, maxPanY) }

    /** Сдвиг на дельту пальца: контент следует за пальцем, поэтому pan уменьшается на смещение. */
    internal fun panBy(dx: Float, dy: Float) {
        applyPanX(panX - dx)
        applyPanY(panY - dy)
    }
}
