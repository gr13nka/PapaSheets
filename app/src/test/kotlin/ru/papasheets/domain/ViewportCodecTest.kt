package ru.papasheets.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.papasheets.matrixgrid.MatrixViewport

/**
 * Кодирование положения матрицы в строку настроек. Главное здесь — [decode] обязан отвергать всё, что
 * не является тремя конечными числами: значение приходит из настроек, переживших обновления приложения
 * и восстановление устройства, а `NaN` в pan/zoom пережил бы и клампинг ([Float.coerceIn] от `NaN` даёт
 * `NaN`) и оставил бы прораба перед пустым экраном без единой ошибки в логе.
 */
class ViewportCodecTest {
    @Test
    fun `encode then decode returns the same viewport`() {
        val viewport = MatrixViewport(panX = 1234.5f, panY = -0.25f, zoom = 0.7f)

        val decoded = ViewportCodec.decode(ViewportCodec.encode(viewport))

        assertEquals(viewport, decoded)
    }

    @Test
    fun `decode returns null when nothing was saved`() {
        assertNull(ViewportCodec.decode(null))
        assertNull(ViewportCodec.decode(""))
    }

    @Test
    fun `decode rejects malformed values`() {
        assertNull(ViewportCodec.decode("мусор"))
        assertNull(ViewportCodec.decode("1;2"))
        assertNull(ViewportCodec.decode("1;2;3;4"))
        assertNull(ViewportCodec.decode("1;2;абв"))
    }

    @Test
    fun `decode rejects non-finite numbers`() {
        assertNull(ViewportCodec.decode("NaN;0.0;1.0"))
        assertNull(ViewportCodec.decode("0.0;Infinity;1.0"))
        assertNull(ViewportCodec.decode("0.0;0.0;-Infinity"))
    }
}
