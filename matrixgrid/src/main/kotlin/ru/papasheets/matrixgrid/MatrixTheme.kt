package ru.papasheets.matrixgrid

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Цвета матрицы. Модуль зависит только от compose ui/foundation (без material3), поэтому палитра
 * задана здесь, а не берётся из MaterialTheme. Светлую/тёмную ветку выбирает [MatrixView] по
 * isSystemInDarkTheme(). Палитра подрядчиков дублирует app-овскую ContractorColors по значениям —
 * в M4 её заменит общий ContractorPalette.
 */
internal class MatrixColors private constructor(
    val background: Color,
    val gridLine: Color,
    val dayDivider: Color,
    val headerBackground: Color,
    val stickyBackground: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val headerText: Color,
) {
    /** Прозрачность тонировки фона ячейки цветом подрядчика. */
    val cellTintAlpha: Float = 0.06f

    /** Прозрачность плейсхолдера фото (пока превью не декодировано). */
    val placeholderAlpha: Float = 0.30f

    /** Прозрачность блока «фото ещё не добавлено» в заполненной ячейке без photoId. */
    val emptyPhotoAlpha: Float = 0.12f

    fun contractor(colorIndex: Int): Color = PALETTE[((colorIndex % PALETTE.size) + PALETTE.size) % PALETTE.size]

    companion object {
        private val PALETTE = listOf(
            Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFB8C00),
            Color(0xFF8E24AA), Color(0xFF00897B), Color(0xFFD81B60), Color(0xFF6D4C41),
            Color(0xFF3949AB), Color(0xFFC0CA33),
        )

        fun of(dark: Boolean): MatrixColors = if (dark) {
            MatrixColors(
                background = Color(0xFF121212),
                gridLine = Color(0xFF2B2B2B),
                dayDivider = Color(0xFF474747),
                headerBackground = Color(0xFF1E1E1E),
                stickyBackground = Color(0xFF1A1A1A),
                primaryText = Color(0xFFECECEC),
                secondaryText = Color(0xFFAFAFAF),
                headerText = Color(0xFFECECEC),
            )
        } else {
            MatrixColors(
                background = Color(0xFFFFFFFF),
                gridLine = Color(0xFFE4E4E4),
                dayDivider = Color(0xFFBDBDBD),
                headerBackground = Color(0xFFF3F3F3),
                stickyBackground = Color(0xFFF7F7F7),
                primaryText = Color(0xFF1F1F1F),
                secondaryText = Color(0xFF5F5F5F),
                headerText = Color(0xFF1F1F1F),
            )
        }
    }
}

/** Типографика матрицы. Цвет в стили не зашит — его задаёт drawText, поэтому кэш layout'ов не зависит от темы. */
internal class MatrixTextStyles {
    val work = TextStyle(fontSize = 12.sp, lineHeight = 15.sp)
    val location = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
    val contractorName = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val subHeader = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp)
    val dayLabel = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}
