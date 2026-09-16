package ru.papasheets.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import ru.papasheets.matrixgrid.GridModel
import ru.papasheets.matrixgrid.MatrixCallbacks
import ru.papasheets.matrixgrid.MatrixView
import ru.papasheets.matrixgrid.ThumbnailSource
import ru.papasheets.matrixgrid.rememberMatrixState

/**
 * Живой кусок матрицы поверх «Настройки группы»: рисует его тот же [MatrixView], что и журнал, а не
 * отдельная картинка под него — поэтому ширина колонки, перенос текста, шрифт шапки и заливка
 * значения выглядят в превью ровно так же, как будут выглядеть на самом деле. Тапы и лонг-пресс никуда
 * не ведут (у превью нет ни записей, ни фото), а pan/pinch остаются рабочими: они безвредны, и
 * выключать их ради одной группы — заводить отдельное урезанное состояние жестов ради этого не стоит.
 */
@Composable
fun GroupPreviewPane(model: GridModel, modifier: Modifier = Modifier) {
    MatrixView(
        model = model,
        state = rememberMatrixState(),
        thumbnails = NoOpThumbnails,
        callbacks = NoOpCallbacks,
        modifier = modifier,
    )
}

private object NoOpThumbnails : ThumbnailSource {
    override val version: State<Int> = mutableStateOf(0)
    override fun peek(key: String): ImageBitmap? = null
    override fun request(key: String, targetPx: Int) = Unit
}

private object NoOpCallbacks : MatrixCallbacks {
    override fun onCellTap(recordId: String) = Unit
    override fun onPhotoTap(recordId: String, slot: Int) = Unit
    override fun onEmptySlotTap(dateEpochDay: Long, contractorId: String) = Unit
    override fun onCellLongPress(recordId: String) = Unit
}
