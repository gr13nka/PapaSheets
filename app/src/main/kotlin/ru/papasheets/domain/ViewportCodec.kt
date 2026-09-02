package ru.papasheets.domain

import ru.papasheets.matrixgrid.MatrixViewport

/**
 * Перевод положения матрицы в одну строку настроек и обратно — без Android, чтобы проверяться
 * JVM-тестами (как [ru.papasheets.domain.export.ExportArchive] при [ru.papasheets.domain.export.ExportFolder]).
 *
 * Единственное нетривиальное здесь — [decode] обязан отсеять испорченное значение, а не пропустить
 * его дальше. Настройки переживают и обновления приложения, и восстановление устройства, так что в
 * ключе может оказаться что угодно; `NaN` же особенно коварен: `coerceIn` от него снова даёт `NaN`,
 * матрица уехала бы в никуда и показала пустой экран — молча, без единой ошибки в логе.
 */
internal object ViewportCodec {
    private const val SEPARATOR = ';'

    fun encode(viewport: MatrixViewport): String =
        "${viewport.panX}$SEPARATOR${viewport.panY}$SEPARATOR${viewport.zoom}"

    /** Разбор сохранённой строки; `null` — «сохранённого положения нет», в том числе если оно негодное. */
    fun decode(raw: String?): MatrixViewport? {
        val parts = raw?.split(SEPARATOR) ?: return null
        if (parts.size != 3) return null
        val numbers = parts.map { it.toFloatOrNull() ?: return null }
        if (numbers.any { !it.isFinite() }) return null
        return MatrixViewport(panX = numbers[0], panY = numbers[1], zoom = numbers[2])
    }
}
