package ru.papasheets.matrixgrid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Положение вьюпорта как значение — ровно то, что нужно, чтобы вернуть матрицу туда, где её оставили.
 * Отделено от [MatrixState] намеренно: состояние живёт в композиции, а значение можно положить в
 * `Bundle` ([MatrixState.Saver]) или в хранилище приложения и пережить им закрытие приложения.
 */
data class MatrixViewport(val panX: Float, val panY: Float, val zoom: Float) {
    companion object {
        /** Начало журнала на детальном зуме — вид, с которого матрица открывается впервые. */
        val Start = MatrixViewport(panX = 0f, panY = 0f, zoom = 1f)
    }
}

/**
 * Держатель состояния прокрутки/зума матрицы. Переживает не только рекомпозицию, но и поворот экрана
 * со смертью процесса: без этого разворот телефона выбрасывал прораба из места, куда он доскроллил,
 * обратно в начало журнала — а именно поворотом он и смотрит широкую матрицу.
 *
 * [initial] — откуда открыть матрицу, когда восстанавливать нечего (первый вход в журнал за запуск);
 * так экран отдаёт сюда позицию, сохранённую с прошлого запуска. При повороте выигрывает [MatrixState.Saver]:
 * [initial] читается только тогда, когда сохранённого состояния композиции нет.
 */
@Composable
fun rememberMatrixState(initial: MatrixViewport = MatrixViewport.Start): MatrixState =
    rememberSaveable(saver = MatrixState.Saver) { MatrixState(initial) }

/**
 * Изменяемое состояние вьюпорта матрицы. Ключевое правило проекта: [panX]/[panY]/[zoom] —
 * snapshot-float'ы, их читают ТОЛЬКО draw- и gesture-лямбды, никогда тело композиции — иначе каждый
 * кадр пинча/прокрутки вызывал бы рекомпозицию.
 *
 * Единственная точка записи pan/zoom — [setTransform]: она клампит значения в границы текущего
 * [geometry]/вьюпорта, так что инвариант «pan/zoom всегда в границах» держится в одном месте, а не
 * размазан по жестам, инерции и анимации. Контекст клампинга публикует рендерер каждый кадр
 * ([updateViewport]) как ОБЫЧНЫЕ поля — их запись не должна инвалидировать draw (см. spec, риск 2),
 * поэтому geometry/viewport здесь не snapshot-состояние.
 *
 * [motionJob] — общий держатель текущей инерции/анимации (fling или анимация обзора): и новый жест, и
 * новое анимирование сперва отменяют предыдущее, чтобы два источника не писали pan вперемешку.
 */
@Stable
class MatrixState(initial: MatrixViewport = MatrixViewport.Start) {
    // Стартовая позиция кладётся как есть, мимо клампа: геометрии на этот момент ещё нет, а
    // восстановленное значение считалось под другой вьюпорт. В границы его дотянет первый же
    // [updateViewport] — тот самый путь, которым уже приходит в себя позиция после поворота.
    private val _panX = mutableFloatStateOf(initial.panX)
    private val _panY = mutableFloatStateOf(initial.panY)
    private val _zoom = mutableFloatStateOf(initial.zoom)

    /** Смещение мира влево внутри тела, экранные px (с учётом зума). Читать только в draw/gesture. */
    val panX: Float get() = _panX.floatValue

    /** Смещение мира вверх внутри тела, экранные px (с учётом зума). Читать только в draw/gesture. */
    val panY: Float get() = _panY.floatValue

    /** Текущий масштаб. Всегда в границах [fit..MAX] благодаря [setTransform]. Читать только в draw/gesture. */
    val zoom: Float get() = _zoom.floatValue

    internal var motionJob: Job? = null

    // Контекст клампинга: геометрия мира и размер кадра. Публикуется рендерером каждый кадр как обычные
    // поля (не snapshot) — их изменение само по себе не должно перезапускать draw.
    private var geometry: MatrixGeometry? = null
    private var viewportW = 0f
    private var viewportH = 0f

    /**
     * «Обзор ли сейчас» — зум ближе к fit, чем к 1:1. Единственное состояние зума, которое МОЖНО читать
     * в композиции (для метки кнопки «Месяц»/«1:1»): derived-значение флипается лишь на пороге обзора,
     * а не каждый кадр пинча, поэтому рекомпозиция происходит редко, а не на каждом кадре зума.
     */
    val isOverview: Boolean by derivedStateOf {
        // zoom читаем ПЕРВЫМ и всегда: иначе при geometry == null (первая композиция кнопки — рендерер
        // ещё не рисовал кадр) derived вернулся бы без snapshot-зависимостей и «застыл» бы навсегда.
        val z = zoom
        val g = geometry ?: return@derivedStateOf false
        z < (g.fitZoom(viewportW, viewportH) + 1f) / 2f
    }

    /**
     * Рендерер/жест публикуют контекст клампинга текущего кадра. Обычная запись полей — без
     * инвалидации draw.
     *
     * Смена контекста (первый кадр, поворот экрана, перестройка модели) переклампливает уже
     * сохранённое pan/zoom: восстановленная из [Saver] позиция считалась под другой вьюпорт и вполне
     * может лежать за границами нового мира, а до первого жеста её никто бы не поправил. Условие на
     * изменение обязательно — переклампливание на каждом кадре писало бы snapshot-состояние прямо
     * из draw.
     */
    internal fun updateViewport(geometry: MatrixGeometry, viewportW: Float, viewportH: Float) {
        val changed = this.geometry !== geometry || this.viewportW != viewportW || this.viewportH != viewportH
        this.geometry = geometry
        this.viewportW = viewportW
        this.viewportH = viewportH
        if (changed) setTransform(zoom, panX, panY)
    }

    /**
     * Единственная точка записи pan/zoom: сперва клампится zoom ([MatrixGeometry.clampZoom]), затем pan
     * по уже клампленному zoom. Все жесты, инерция и анимация обзора пишут только через неё. До первого
     * кадра (geometry ещё нет) значения сохраняются как есть.
     */
    internal fun setTransform(zoom: Float, panX: Float, panY: Float) {
        val g = geometry
        if (g == null) {
            _zoom.floatValue = zoom
            _panX.floatValue = panX
            _panY.floatValue = panY
            return
        }
        val z = g.clampZoom(zoom, viewportW, viewportH)
        _zoom.floatValue = z
        _panX.floatValue = g.clampPanX(panX, viewportW, z)
        _panY.floatValue = g.clampPanY(panY, viewportH, z)
    }

    /** Сдвиг pan на дельту пальца при неизменном zoom: контент следует за пальцем, поэтому pan уменьшается. */
    internal fun panBy(dx: Float, dy: Float) = setTransform(zoom, panX - dx, panY - dy)

    /**
     * Снимок положения для сохранения между запусками. Читать ТОЛЬКО вне композиции (lifecycle-колбэк,
     * `onDispose`): вызов из тела композиции подпишет экран на snapshot-float'ы, и каждый кадр
     * прокрутки вызывал бы рекомпозицию — ровно то, чего избегает всё устройство этого класса.
     */
    fun viewport(): MatrixViewport = MatrixViewport(panX = panX, panY = panY, zoom = zoom)

    /**
     * Сбрасывает pan к началу мира на текущем зуме, отменяя любую текущую инерцию/анимацию. Нужен
     * после перестройки раскладки, где старая позиция теряет смысл (например, разворот сортировки
     * дат меняет порядок строк местами) — публичный вход для UI, в отличие от [setTransform].
     */
    fun jumpToStart() {
        motionJob?.cancel()
        setTransform(zoom, 0f, 0f)
    }

    /** Клампит zoom по текущему контексту — нужен пивот-математике пинча, чтобы pan считался под тот же zoom, что запишется. */
    internal fun clampedZoom(zoom: Float): Float =
        geometry?.clampZoom(zoom, viewportW, viewportH) ?: zoom

    /**
     * Публичный вход для UI: анимированный тумблер «вся картина месяца» (fit) ↔ детальный зум (1:1) с
     * фокусом в центре кадра. Тот же код-путь, что и double-tap ([toggleOverviewAt]) — кнопка topBar и
     * жест ведут себя одинаково. Отменяет текущую инерцию/анимацию через общий [motionJob].
     */
    fun toggleOverview(scope: CoroutineScope) = toggleOverviewAt(scope, viewportW / 2f, viewportH / 2f)

    /** Тумблер обзора с фокусом в точке (double-tap передаёт точку касания). */
    internal fun toggleOverviewAt(scope: CoroutineScope, focusX: Float, focusY: Float) {
        motionJob?.cancel()
        motionJob = scope.launch { animateToggleFit(focusX, focusY) }
    }

    /**
     * Анимация тумблера fit↔1.0 с фокусом в (focusX, focusY): на каждом кадре pan пересчитывается из
     * пивота ([MatrixGeometry.panXForZoom]/[panYForZoom]), поэтому точка под фокусом стоит на месте всю
     * анимацию. Пишет только через [setTransform] — клампинг единый.
     */
    private suspend fun animateToggleFit(focusX: Float, focusY: Float) {
        val g = geometry ?: return
        val fit = g.fitZoom(viewportW, viewportH)
        val start = g.clampZoom(zoom, viewportW, viewportH)
        val target = if (abs(start - fit) < abs(start - 1f)) 1f else fit
        if (abs(target - start) < 0.001f) return
        val startPanX = panX
        val startPanY = panY
        Animatable(0f).animateTo(1f, animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)) {
            val z = start + (target - start) * value
            setTransform(z, g.panXForZoom(focusX, startPanX, start, z), g.panYForZoom(focusY, startPanY, start, z))
        }
    }

    companion object {
        /**
         * Сохраняется только [MatrixViewport]. Контекст клампинга (geometry, размеры кадра) не
         * сохраняется намеренно: после поворота он другой, и восстановленную позицию всё равно
         * пришлось бы пересчитывать — этим занимается [updateViewport] на первом же кадре.
         */
        val Saver = listSaver<MatrixState, Float>(
            save = { listOf(it.panX, it.panY, it.zoom) },
            restore = { saved ->
                MatrixState(MatrixViewport(panX = saved[0], panY = saved[1], zoom = saved[2]))
            },
        )
    }
}
