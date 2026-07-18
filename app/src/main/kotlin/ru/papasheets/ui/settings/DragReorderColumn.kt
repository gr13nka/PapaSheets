package ru.papasheets.ui.settings

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/**
 * Список с перетаскиванием строк за ручку «≡»: долгое нажатие начинает жест, порядок меняется по мере
 * прохождения половины высоты соседней строки, на отпускании — persist через [onReorder].
 *
 * Общий для настроек подрядчиков и полей: в обоих случаях порядок строк на экране и есть порядок
 * колонок матрицы, и вести себя это должно одинаково. Без стороннних библиотек — стандартный паттерн
 * на `detectDragGesturesAfterLongPress`.
 *
 * @param rowHeight фиксированная высота строки — жест переводит смещение в позиции по ней, поэтому
 *   [row] обязан рисовать строку именно такой высоты, иначе перетаскивание разъедется с картинкой.
 * @param row строка списка; переданный `dragHandle` нужно разместить внутри — только он ловит жест.
 */
@Composable
fun <T> DragReorderColumn(
    items: List<T>,
    key: (T) -> Any,
    rowHeight: Dp,
    onReorder: (List<T>) -> Unit,
    row: @Composable (item: T, dragHandle: @Composable () -> Unit) -> Unit,
) {
    val itemHeightPx = with(LocalDensity.current) { rowHeight.toPx() }
    // Переключается на новый список извне (после persist Room эхом пришлёт тот же порядок).
    var order by remember(items) { mutableStateOf(items) }
    var draggingKey by remember { mutableStateOf<Any?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    Column {
        // key(...) закрепляет composable (и его pointerInput-корутину) за identity строки, а не за
        // позицией в дереве: без него свап позиций в onDrag ниже пересоздал бы узел на месте
        // перетаскиваемой строки (Compose увидел бы там "новые" данные) и оборвал бы жест
        // onDragCancel'ом ДО onDragEnd — свап был бы виден на экране, но onReorder (persist) так и
        // не вызвался бы.
        order.forEach { item ->
            val itemKey = key(item)
            key(itemKey) {
                val isDragging = itemKey == draggingKey
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragging) dragOffset else 0f },
                ) {
                    row(item) {
                        Text(
                            text = "≡",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .padding(8.dp)
                                .pointerInput(itemKey) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { draggingKey = itemKey; dragOffset = 0f },
                                        onDragEnd = {
                                            draggingKey = null
                                            dragOffset = 0f
                                            onReorder(order)
                                        },
                                        onDragCancel = { draggingKey = null; dragOffset = 0f },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffset += dragAmount.y
                                            val from = order.indexOfFirst { key(it) == itemKey }
                                            val to = (from + (dragOffset / itemHeightPx).roundToInt())
                                                .coerceIn(0, order.lastIndex)
                                            if (to != from) {
                                                order = order.toMutableList().apply { add(to, removeAt(from)) }
                                                dragOffset -= (to - from) * itemHeightPx
                                            }
                                        },
                                    )
                                },
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
