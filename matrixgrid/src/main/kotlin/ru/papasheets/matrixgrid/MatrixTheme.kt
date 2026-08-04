package ru.papasheets.matrixgrid

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Цвета матрицы. Модуль зависит только от compose ui/foundation (без material3), поэтому палитра
 * задана здесь, а не берётся из MaterialTheme. Светлую/тёмную ветку выбирает [MatrixView] по
 * isSystemInDarkTheme(). Цвета подрядчиков берутся из общего [MatrixPalette] — единый источник
 * и для матрицы, и для списка записей в app.
 */
internal class MatrixColors private constructor(
    private val dark: Boolean,
    val background: Color,
    val gridLine: Color,
    val dayDivider: Color,
    val headerBackground: Color,
    val stickyBackground: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val headerText: Color,
) {
    /** Прозрачность тонировки фона ячейки цветом подрядчика (LOD0/LOD1). */
    val cellTintAlpha: Float = 0.06f

    /** Прозрачность плейсхолдера фото (пока превью не декодировано). */
    val placeholderAlpha: Float = 0.30f

    /** Прозрачность блока «фото ещё не добавлено» в заполненной ячейке без photoId. */
    val emptyPhotoAlpha: Float = 0.12f

    /**
     * Прозрачность заливки подколонки цветом её значения. Заметно сильнее тонировки подрядчика
     * ([cellTintAlpha]) — иначе цвет вида работ терялся бы на её фоне, — но приглушено: поверх
     * заливки читается текст самого значения.
     */
    val valueFillAlpha: Float = 0.22f

    /** Цвет подрядчика (с учётом темы). Полная непрозрачность — блок «картины месяца» LOD2. */
    fun contractor(colorIndex: Int): Color = MatrixPalette.color(colorIndex, dark)

    /** Цвет значения поля (с учётом темы) — из той же палитры, что и цвета подрядчиков. */
    fun value(colorIndex: Int): Color = MatrixPalette.color(colorIndex, dark)

    companion object {
        fun of(dark: Boolean): MatrixColors = if (dark) {
            MatrixColors(
                dark = true,
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
                dark = false,
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
    /** Второстепенные поля ячейки: подробности, читаемые уже после того, как глаз нашёл строку. */
    val cellSecondary = TextStyle(fontSize = 12.sp, lineHeight = 15.sp)

    /** Первое поле ячейки — опорное при беглом просмотре колонки, поэтому крупнее и жирнее. */
    val cellPrimary = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
    val contractorName = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val subHeader = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp)
    val dayLabel = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

    /**
     * Стиль значения поля по его позиции в группе. Правило одно на весь модуль (и для замера высот
     * строк, и для отрисовки), поэтому живёт здесь, а не дублируется у обоих вызывающих.
     */
    fun cell(fieldIndex: Int): TextStyle = if (fieldIndex == 0) cellPrimary else cellSecondary
}
