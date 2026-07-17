package ru.papasheets.matrixgrid

import android.util.LruCache
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

/**
 * Кэш результатов измерения текста — главная цена Canvas-рендера (см. spec, риск 3). Замер идёт лишь
 * для впервые показанной ячейки, дальше берётся из кэша.
 *
 * Текст всегда измеряется в базовом масштабе (zoom = 1) с фиксированными по типу колонки ширинами;
 * промежуточный зум внутри яруса рендерер добирает canvas-трансформом
 * [androidx.compose.ui.graphics.drawscope.DrawScope.scale]. Поэтому раскладка не зависит от зума, и в
 * живом пинче перемера нет вовсе. Ярус (LOD) решает лишь, какие тексты вообще нужны: LOD0 — и локация,
 * и вид работ; LOD1 — только локация; LOD2 — ничего. Отсюда кэши разбиты по типу текста
 * ([work]/[location]), а не по составному ключу (recordId, ярус): это и есть разбиение «по ярусу», но
 * без склейки строки-ключа в горячем цикле (recordId — уже готовый ключ). Статические подписи (имена
 * подрядчиков, «Ф/Л/ВИД РАБОТ», метки дат) живут отдельной небольшой картой.
 *
 * Инвалидация — при смене экземпляра [GridModel] (builder создаёт новый на каждое изменение данных).
 * Идентичность экземпляра, а не хэш содержимого: он не заметил бы правку текста ячейки при неизменном
 * числе записей. Смена данных происходит по действию пользователя, не в кадре пинча, поэтому пересбор
 * видимых замеров дёшев и безопасен.
 */
internal class CellTextCache {
    private var modelToken: Any? = null
    private val work = LruCache<String, TextLayoutResult>(1024)
    private val location = LruCache<String, TextLayoutResult>(1024)
    private val statics = HashMap<String, TextLayoutResult>()

    /** Сбрасывает кэши при смене экземпляра модели. Вызывается в начале кадра — no-op при той же модели. */
    fun sync(token: Any) {
        if (token !== modelToken) {
            modelToken = token
            work.evictAll()
            location.evictAll()
            statics.clear()
        }
    }

    /** Код локации ячейки (LOD0/LOD1). Ключ — recordId: ширина фиксирована, ярус на замер не влияет. */
    fun location(
        measurer: TextMeasurer,
        recordId: String,
        text: String,
        style: TextStyle,
        widthPx: Int,
    ): TextLayoutResult {
        location.get(recordId)?.let { return it }
        val measured = measurer.measure(
            text = text,
            style = style,
            overflow = TextOverflow.Ellipsis,
            maxLines = 2,
            constraints = Constraints(maxWidth = widthPx.coerceAtLeast(0)),
        )
        location.put(recordId, measured)
        return measured
    }

    /** Вид работ ячейки (только LOD0). Ключ — recordId. */
    fun work(
        measurer: TextMeasurer,
        recordId: String,
        text: String,
        style: TextStyle,
        widthPx: Int,
    ): TextLayoutResult {
        work.get(recordId)?.let { return it }
        val measured = measurer.measure(
            text = text,
            style = style,
            overflow = TextOverflow.Ellipsis,
            maxLines = 3,
            constraints = Constraints(maxWidth = widthPx.coerceAtLeast(0)),
        )
        work.put(recordId, measured)
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
