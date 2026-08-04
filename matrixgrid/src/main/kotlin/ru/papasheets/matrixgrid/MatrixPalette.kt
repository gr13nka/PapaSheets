package ru.papasheets.matrixgrid

import androidx.compose.ui.graphics.Color

/**
 * Единая палитра различимых цветов (`colorIndex` → цвет) для всего приложения: и матрицы, и списка
 * записей в app. 10 базовых оттенков; тёмная ветка слегка приглушена, чтобы сплошные блоки LOD2 и
 * тонировка ячеек читались на тёмном фоне [MatrixColors]. Индекс заворачивается по модулю — цвет за
 * её пределами переиспользуется (палитра — акцент, а не идентификатор).
 */
object MatrixPalette {
    private val light = listOf(
        Color(0xFFE53935), // красный
        Color(0xFF1E88E5), // синий
        Color(0xFF43A047), // зелёный
        Color(0xFFFB8C00), // оранжевый
        Color(0xFF8E24AA), // фиолетовый
        Color(0xFF00897B), // бирюзовый
        Color(0xFFD81B60), // розовый
        Color(0xFF6D4C41), // коричневый
        Color(0xFF3949AB), // индиго
        Color(0xFFC0CA33), // лайм
    )

    // Те же оттенки, поднятые по светлоте и чуть приглушённые под тёмный фон матрицы (#121212).
    private val dark = listOf(
        Color(0xFFEF5350), // красный
        Color(0xFF42A5F5), // синий
        Color(0xFF66BB6A), // зелёный
        Color(0xFFFFA726), // оранжевый
        Color(0xFFAB47BC), // фиолетовый
        Color(0xFF26A69A), // бирюзовый
        Color(0xFFEC407A), // розовый
        Color(0xFF8D6E63), // коричневый
        Color(0xFF5C6BC0), // индиго
        Color(0xFFD4E157), // лайм
    )

    val size: Int get() = light.size

    fun color(colorIndex: Int, dark: Boolean): Color =
        (if (dark) this.dark else light)[((colorIndex % size) + size) % size]
}
