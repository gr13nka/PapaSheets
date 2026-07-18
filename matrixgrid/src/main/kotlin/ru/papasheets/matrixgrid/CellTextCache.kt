package ru.papasheets.matrixgrid

import android.util.LruCache
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

/** Суммарный бюджет замеров ячеек на все поля вместе. */
private const val CELL_BUDGET = 2048

/** Нижняя граница бюджета одного поля: даже при многих полях кэш должен покрывать экран целиком. */
private const val FIELD_BUDGET_MIN = 256

/**
 * Кэш результатов измерения текста — главная цена Canvas-рендера (см. spec, риск 3). Замер идёт лишь
 * для впервые показанной ячейки, дальше берётся из кэша.
 *
 * Текст всегда измеряется в базовом масштабе (zoom = 1) с фиксированной по полю шириной;
 * промежуточный зум внутри яруса рендерер добирает canvas-трансформом
 * [androidx.compose.ui.graphics.drawscope.DrawScope.scale]. Поэтому раскладка не зависит от зума, и в
 * живом пинче перемера нет вовсе. Ярус (LOD) решает лишь, какие поля вообще нужны (см.
 * [GridField.showAtCompactLod]). Отсюда кэши разбиты ПО ИНДЕКСУ ПОЛЯ, а не по составному ключу
 * (recordId, поле): recordId — уже готовый ключ, а склейка строки-ключа в горячем цикле недопустима.
 * Статические подписи (имена подрядчиков, заголовки полей, метки дат) живут отдельной небольшой картой.
 *
 * Инвалидация — при смене экземпляра [GridModel] (builder создаёт новый на каждое изменение данных).
 * Идентичность экземпляра, а не хэш содержимого: он не заметил бы правку текста ячейки при неизменном
 * числе записей. Смена данных происходит по действию пользователя, не в кадре пинча, поэтому пересбор
 * видимых замеров дёшев и безопасен.
 */
internal class CellTextCache {
    private var model: GridModel? = null
    private var fields: Array<LruCache<String, TextLayoutResult>> = emptyArray()
    private val statics = HashMap<String, TextLayoutResult>()

    /**
     * Сбрасывает кэши при смене экземпляра модели и подгоняет число кэшей под её поля.
     * Вызывается в начале кадра — no-op при той же модели.
     */
    fun sync(model: GridModel) {
        if (model === this.model) return
        this.model = model
        val budget = (CELL_BUDGET / maxOf(1, model.fields.size)).coerceAtLeast(FIELD_BUDGET_MIN)
        fields = Array(model.fields.size) { LruCache(budget) }
        statics.clear()
    }

    /**
     * Значение поля [fieldIndex] в ячейке [recordId]. Ключ — recordId: ширина поля фиксирована,
     * ярус на замер не влияет.
     */
    fun field(
        measurer: TextMeasurer,
        fieldIndex: Int,
        recordId: String,
        text: String,
        style: TextStyle,
        widthPx: Int,
        maxLines: Int,
    ): TextLayoutResult {
        val cache = fields[fieldIndex]
        cache.get(recordId)?.let { return it }
        val measured = measurer.measure(
            text = text,
            style = style,
            overflow = TextOverflow.Ellipsis,
            maxLines = maxLines,
            constraints = Constraints(maxWidth = widthPx.coerceAtLeast(0)),
        )
        cache.put(recordId, measured)
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
