package ru.papasheets.matrixgrid

import android.util.LruCache
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

/** Измеренный текст одной заполненной ячейки: вид работ (многострочный) и код локации. */
internal class CellText(val work: TextLayoutResult, val location: TextLayoutResult)

/**
 * Кэш результатов измерения текста — главная цена Canvas-рендера (см. spec, риск 3). Замер идёт лишь
 * для впервые показанной ячейки, дальше берётся из кэша. Ключ ячейки — recordId (ширина в M3
 * фиксирована), поэтому в горячем пути на кадр не создаётся строк-ключей. Статические подписи
 * (имена подрядчиков, «Ф/Л/ВИД РАБОТ», метки дат) живут отдельной небольшой картой.
 *
 * Инвалидация — при смене экземпляра [GridModel] (builder создаёт новый на каждое изменение
 * данных). Идентичность, а не [GridModel.contentKey]: последний считает лишь строки/подрядчиков и
 * не заметил бы правку текста ячейки при неизменном числе записей. Смена данных происходит по
 * действию пользователя, не в кадре прокрутки, поэтому пересбор видимых замеров дёшев и безопасен.
 */
internal class CellTextCache {
    private var modelToken: Any? = null
    private val cells = LruCache<String, CellText>(1024)
    private val statics = HashMap<String, TextLayoutResult>()

    /** Сбрасывает кэши при смене экземпляра модели. Вызывается в начале кадра — no-op при той же модели. */
    fun sync(token: Any) {
        if (token !== modelToken) {
            modelToken = token
            cells.evictAll()
            statics.clear()
        }
    }

    fun cell(
        measurer: TextMeasurer,
        recordId: String,
        workText: String,
        locationText: String,
        workStyle: TextStyle,
        locationStyle: TextStyle,
        workWidthPx: Int,
        locationWidthPx: Int,
    ): CellText {
        cells.get(recordId)?.let { return it }
        val measured = CellText(
            work = measurer.measure(
                text = workText,
                style = workStyle,
                overflow = TextOverflow.Ellipsis,
                maxLines = 3,
                constraints = Constraints(maxWidth = workWidthPx.coerceAtLeast(0)),
            ),
            location = measurer.measure(
                text = locationText,
                style = locationStyle,
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
                constraints = Constraints(maxWidth = locationWidthPx.coerceAtLeast(0)),
            ),
        )
        cells.put(recordId, measured)
        return measured
    }

    /** Измеряет и запоминает статичную подпись по стабильному строковому ключу [key]. */
    fun static(
        measurer: TextMeasurer,
        key: String,
        text: String,
        style: TextStyle,
        maxWidthPx: Int,
        maxLines: Int,
    ): TextLayoutResult {
        statics[key]?.let { return it }
        val measured = measurer.measure(
            text = text,
            style = style,
            overflow = TextOverflow.Ellipsis,
            maxLines = maxLines,
            constraints = Constraints(maxWidth = maxWidthPx.coerceAtLeast(0)),
        )
        statics[key] = measured
        return measured
    }
}
