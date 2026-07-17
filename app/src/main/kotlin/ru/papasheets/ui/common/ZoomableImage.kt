package ru.papasheets.ui.common

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f

/**
 * Полноэкранное фото лайтбокса с собственным pinch-zoom/pan — единственное место в приложении,
 * где это нужно (матрица зумируется по-другому, через свой Canvas-рендер в M3/M4).
 * Пинч 1x..5x вокруг центроида, pan с clamp по краям, double-tap 1x↔2.5x.
 */
@Composable
fun ZoomableImage(model: Any?, contentDescription: String?, modifier: Modifier = Modifier) {
    var scale by remember { mutableFloatStateOf(MIN_SCALE) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(candidate: Offset, currentScale: Float): Offset {
        val maxX = (containerSize.width * (currentScale - 1) / 2f).coerceAtLeast(0f)
        val maxY = (containerSize.height * (currentScale - 1) / 2f).coerceAtLeast(0f)
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }

    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .onSizeChanged { containerSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tapPoint ->
                        val target = if (scale > MIN_SCALE) MIN_SCALE else DOUBLE_TAP_SCALE
                        offset = if (target == MIN_SCALE) {
                            Offset.Zero
                        } else {
                            clamp((offset - tapPoint) * (target / scale) + tapPoint, target)
                        }
                        scale = target
                    },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                    // Сдвигаем offset так, чтобы точка под пальцами (центроид) оставалась на месте при масштабировании.
                    offset = clamp((offset - centroid) * (newScale / scale) + centroid + pan, newScale)
                    scale = newScale
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}
