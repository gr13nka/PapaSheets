package ru.papasheets.ui.common

import androidx.compose.ui.graphics.Color

/** Фиксированная палитра цветов подрядчика по `colorIndex` — общая для списка записей (M1) и матрицы (M4). */
object ContractorColors {
    private val palette = listOf(
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

    fun forIndex(colorIndex: Int): Color = palette[colorIndex.mod(palette.size)]
}
