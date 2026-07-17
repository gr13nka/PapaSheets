package ru.papasheets.photos

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

/**
 * [ThumbnailSource] матрицы поверх [PhotoStore]: декодирует thumb-файлы (256px) в [ImageBitmap],
 * держит их в LRU по числу байт и по готовности инкрементит [version], чем провоцирует перерисовку.
 *
 * Весь публичный контракт вызывается из draw-прохода на главном потоке: [peek] синхронно читает кэш,
 * [request] дедуплицирует заявки (одна декодировка на ключ) и уводит саму декодировку на IO.
 * Инкремент [version] и запись в кэш возвращаются на главный поток — иначе snapshot-подписка draw
 * не увидела бы изменения как redraw. Поэтому [inFlight] трогается только с главного потока.
 */
class BitmapThumbnailSource(
    private val photoStore: PhotoStore,
    private val scope: CoroutineScope,
) : ThumbnailSource {

    private val cache = object : LruCache<String, ImageBitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }
    private val inFlight = HashSet<String>()

    private val _version = mutableIntStateOf(0)
    override val version: State<Int> = _version

    override fun peek(key: String): ImageBitmap? = cache.get(key)

    override fun request(key: String, targetPx: Int) {
        if (cache.get(key) != null || !inFlight.add(key)) return
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { decode(key, targetPx) }
            inFlight.remove(key)
            if (bitmap != null) {
                cache.put(key, bitmap)
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
}
