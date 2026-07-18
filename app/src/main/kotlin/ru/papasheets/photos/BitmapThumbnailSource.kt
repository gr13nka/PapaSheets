package ru.papasheets.photos

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.papasheets.matrixgrid.ThumbnailSource

/** Максимальный размер кэша декодированных превью в байтах (~48 МБ, см. spec, риск 1). */
private const val CACHE_BYTES = 48 * 1024 * 1024

/** Микро-ступень декода (LOD1): сторона превью ~128px. */
private const val STEP_MICRO = 128

/** Полная ступень (LOD0): исходный thumb-файл 256px, декод без даунсемпла. */
private const val STEP_FULL = 256

/**
 * [ThumbnailSource] матрицы поверх [PhotoStore]: декодирует thumb-файлы (256px) в [ImageBitmap],
 * держит их в LRU по числу байт и по готовности инкрементит [version], чем провоцирует перерисовку.
 *
 * targetPx запросов зависит от LOD (микро-превью на LOD1, полное на LOD0), поэтому он нормализуется к
 * двум ступеням [STEP_MICRO]/[STEP_FULL]. На ключ всегда держится ровно одна запись: если пришёл
 * запрос на ступень крупнее уже декодированной (зум-ин), превью пере-декодируется вверх и заменяет
 * прежнее — дубликаты в LRU не копятся, а очередь на зум-ауте не растёт (на LOD2 рендерер request не
 * зовёт вовсе). [decodedStep] очищается вместе с вытеснением записи из LRU ([entryRemoved]).
 *
 * Весь публичный контракт вызывается из draw-прохода на главном потоке: [peek] синхронно читает кэш,
 * [request] дедуплицирует заявки (одна декодировка на ключ) и уводит саму декодировку на IO. Инкремент
 * [version] и запись в кэш возвращаются на главный поток — иначе snapshot-подписка draw не увидела бы
 * изменения как redraw. Поэтому [inFlight]/[decodedStep] трогаются только с главного потока.
 *
 * Как [ComponentCallbacks2] сам сбрасывает LRU под нехваткой памяти и при уходе в фон (см. spec, риск 1):
 * регистрируется в приложении при создании и снимается [dispose] при уходе экрана (MatrixViewModel.onCleared).
 * Колбэки приходят на главном потоке, так что инвариант «кэш только с главного потока» не нарушается.
 */
class BitmapThumbnailSource(
    private val photoStore: PhotoStore,
    private val scope: CoroutineScope,
    context: Context,
) : ThumbnailSource, ComponentCallbacks2 {

    private val appContext = context.applicationContext.also { it.registerComponentCallbacks(this) }

    private val decodedStep = HashMap<String, Int>()
    private val cache = object : LruCache<String, ImageBitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
        override fun entryRemoved(evicted: Boolean, key: String, old: ImageBitmap, new: ImageBitmap?) {
            if (new == null) decodedStep.remove(key)
        }
    }
    private val inFlight = HashSet<String>()

    private val _version = mutableIntStateOf(0)
    override val version: State<Int> = _version

    override fun peek(key: String): ImageBitmap? = cache.get(key)

    override fun request(key: String, targetPx: Int) {
        val step = if (targetPx <= STEP_MICRO) STEP_MICRO else STEP_FULL
        val have = decodedStep[key] ?: 0
        if (cache.get(key) != null && have >= step) return // уже есть не хуже нужного
        if (!inFlight.add(key)) return
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { decode(key, step) }
            inFlight.remove(key)
            if (bitmap != null) {
                cache.put(key, bitmap)
                decodedStep[key] = step
                _version.intValue++
            }
        }
    }

    private fun decode(key: String, targetPx: Int): ImageBitmap? {
        val file = photoStore.thumbFile(key)
        if (!file.exists()) return null
        val path = file.absolutePath
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(max(bounds.outWidth, bounds.outHeight), targetPx)
        }
        return BitmapFactory.decodeFile(path, options)?.asImageBitmap()
    }

    /** Наибольшая степень двойки, при которой сторона всё ещё не мельче [targetPx]. */
    private fun sampleSize(sourcePx: Int, targetPx: Int): Int {
        if (targetPx <= 0) return 1
        var sample = 1
        while (sourcePx / (sample * 2) >= targetPx) sample *= 2
        return sample
    }

    // При заметной нехватке памяти или уходе приложения в фон отдаём ~48 МБ превью системе —
    // они дёшево пере-декодируются из thumb-файлов при следующем кадре.
    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) cache.evictAll()
    }

    override fun onLowMemory() = cache.evictAll()

    override fun onConfigurationChanged(newConfig: Configuration) { /* превью не зависят от конфигурации */ }

    /** Снимает подписку на системные колбэки и освобождает кэш. Зовётся при уходе экрана (VM.onCleared). */
    fun dispose() {
        appContext.unregisterComponentCallbacks(this)
        cache.evictAll()
    }
}
