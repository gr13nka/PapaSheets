package ru.papasheets.matrixgrid

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-тесты чистой математики раскладки: клампинг pan (в т.ч. центрирование малого мира), выбор яруса,
 * hit-тест с учётом клампленных шапок на слабом зуме и инвариант пивота пинча. Плотность 2.0 выбрана
 * ради круглых пикселей: dp просто удваиваются.
 */
class MatrixGeometryTest {
    private val density = Density(2f)

    // Большой мир (как «Январь 2000»): 28 групп × 300 строк.
    private fun bigGeometry() = MatrixGeometry(density, rowCount = 300, groupCount = 28)

    // Мир меньше вьюпорта — для проверки центрирования.
    private fun tinyGeometry() = MatrixGeometry(density, rowCount = 1, groupCount = 1)

    private val viewportW = 1080f
    private val viewportH = 2000f

    @Test
    fun lodBoundariesFollowZoom() {
        assertEquals(Lod.LOD0, Lod.forZoom(2.0f))
        assertEquals(Lod.LOD0, Lod.forZoom(1.0f))
        assertEquals(Lod.LOD0, Lod.forZoom(Lod.DETAIL_MIN_ZOOM))
        assertEquals(Lod.LOD1, Lod.forZoom(Lod.DETAIL_MIN_ZOOM - 0.01f))
        assertEquals(Lod.LOD1, Lod.forZoom(Lod.COMPACT_MIN_ZOOM))
        assertEquals(Lod.LOD2, Lod.forZoom(Lod.COMPACT_MIN_ZOOM - 0.01f))
        assertEquals(Lod.LOD2, Lod.forZoom(0.05f))
    }

    @Test
    fun panClampsToWorldBoundsAtZoomOne() {
        val g = bigGeometry()
        val zoom = 1f
        // dateColW и headerH при zoom=1 не клампятся (базовые > минимальных).
        assertEquals(128f, g.dateColW(zoom), 0.01f)
        assertEquals(96f, g.headerH(zoom), 0.01f)

        val maxX = g.maxPanX(viewportW, zoom)
        assertEquals(0f, g.minPanX(viewportW, zoom), 0.01f)
        assertEquals(g.worldWidth - (viewportW - 128f), maxX, 0.01f)

        assertEquals(0f, g.clampPanX(-500f, viewportW, zoom), 0.01f)
        assertEquals(maxX, g.clampPanX(maxX + 9999f, viewportW, zoom), 0.01f)
        assertEquals(1000f, g.clampPanX(1000f, viewportW, zoom), 0.01f)
    }

    @Test
    fun smallWorldCentersInsteadOfScrolling() {
        val g = tinyGeometry()
        val zoom = 1f
        val bodyW = viewportW - g.dateColW(zoom) // 1080 - 128 = 952
        val expectedPan = (g.worldWidth * zoom - bodyW) / 2f // (592 - 952)/2 = -180

        // Мир уже тела: pan заперт в центр — min == max.
        assertEquals(g.minPanX(viewportW, zoom), g.maxPanX(viewportW, zoom), 0.001f)
        assertEquals(expectedPan, g.clampPanX(0f, viewportW, zoom), 0.01f)
        assertEquals(expectedPan, g.clampPanX(5000f, viewportW, zoom), 0.01f)

        // Мировая точка W=0 действительно оказывается по центру тела.
        val worldLeftScreen = g.dateColW(zoom) - expectedPan
        val worldRightScreen = worldLeftScreen + g.worldWidth * zoom
        assertEquals(viewportW - worldRightScreen, worldLeftScreen - g.dateColW(zoom), 0.01f)
    }

    @Test
    fun hitTestUsesClampedHeadersWhenZoomedOut() {
        val g = bigGeometry()
        val zoom = 0.1f
        // На слабом зуме шапки клампятся к минимуму.
        assertEquals(68f, g.dateColW(zoom), 0.01f) // dateColMinW
        assertEquals(60f, g.headerH(zoom), 0.01f) // headerMinH

        // Точки в клампленных шапке/колонке дат — не тело.
        assertTrue(g.hitTest(40f, 80f, 0f, 0f, zoom) is MatrixHit.Other) // x < dateColW
        assertTrue(g.hitTest(200f, 30f, 0f, 0f, zoom) is MatrixHit.Other) // y < headerH

        // Точка в теле: worldX=30 → группа 0 (groupPx=59.2), worldY=20 → строка 1 (rowPx=15.2).
        val body = g.hitTest(98f, 80f, 0f, 0f, zoom)
        assertTrue(body is MatrixHit.Body)
        body as MatrixHit.Body
        assertEquals(0, body.group)
        assertEquals(1, body.row)
        assertEquals(false, body.onPhoto) // localX=30 > photoColW*zoom=14.4

        // Та же точка в Ф-подколонке (localX=5 < 14.4), но zoom=0.1 — это LOD2: фото не рисуются,
        // поэтому фото-зона отключена (onPhoto=false), см. photoZoneOnlyAtDetailTiers.
        val photoCell = g.hitTest(73f, 65f, 0f, 0f, zoom) as MatrixHit.Body
        assertEquals(false, photoCell.onPhoto)
        assertEquals(0, photoCell.row)
        assertEquals(0, photoCell.group)
    }

    @Test
    fun photoZoneOnlyAtDetailTiers() {
        val g = bigGeometry()
        // LOD0 (zoom=1): точка в Ф-подколонке группы 0 → фото-зона активна.
        val lod0 = g.hitTest(140f, 100f, 0f, 0f, 1f) as MatrixHit.Body
        assertEquals(0, lod0.group)
        assertTrue(lod0.onPhoto)
        // LOD1 (zoom=0.5): точка в Ф-подколонке → фото-зона активна.
        val lod1 = g.hitTest(90f, 70f, 0f, 0f, 0.5f) as MatrixHit.Body
        assertEquals(0, lod1.group)
        assertTrue(lod1.onPhoto)
        // LOD2 (zoom=0.1): та же зона, но битмапы не рисуются → onPhoto=false.
        val lod2 = g.hitTest(73f, 65f, 0f, 0f, 0.1f) as MatrixHit.Body
        assertEquals(false, lod2.onPhoto)
    }

    @Test
    fun hitTestAgreesWithRendererWhenWorldCentered() {
        // Мир уже́ вьюпорта (как на fit-обзоре) → центрируется. Hit-тест обязан попадать ровно туда, куда
        // рендерер рисует ячейку: берём экранный центр группы из groupScreenLeft/rowScreenTop и проверяем,
        // что hitTest возвращает ту же (группа, строка). Так ловится «разъезд» hit-теста при центрировании.
        val g = tinyGeometry()
        val zoom = 1f
        val panX = g.minPanX(viewportW, zoom) // при центрировании min == max == позиция центра
        val panY = g.minPanY(viewportH, zoom)
        val cx = g.groupScreenLeft(0, panX, zoom) + g.groupW * zoom / 2f
        val cy = g.rowScreenTop(0, panY, zoom) + g.rowH * zoom / 2f

        val hit = g.hitTest(cx, cy, panX, panY, zoom) as MatrixHit.Body
        assertEquals(0, hit.group)
        assertEquals(0, hit.row)
        // Левее нарисованной группы — поле центрирования, не тело.
        assertTrue(g.hitTest(g.groupScreenLeft(0, panX, zoom) - 5f, cy, panX, panY, zoom) is MatrixHit.Other)
    }

    @Test
    fun pinchPivotKeepsWorldPointUnderFingerX() {
        val g = bigGeometry()
        // При смене зума мировая точка под pivotX должна остаться на pivotX (до клампа).
        assertPivotStableX(g, pivot = 500f, panOld = 200f, zoomOld = 1f, zoomNew = 1.5f)
        assertPivotStableX(g, pivot = 800f, panOld = 1500f, zoomOld = 0.9f, zoomNew = 0.3f)
        assertPivotStableX(g, pivot = 300f, panOld = 0f, zoomOld = 0.5f, zoomNew = 0.1f)
    }

    @Test
    fun pinchPivotKeepsWorldPointUnderFingerY() {
        val g = bigGeometry()
        assertPivotStableY(g, pivot = 900f, panOld = 400f, zoomOld = 1f, zoomNew = 1.8f)
        assertPivotStableY(g, pivot = 1200f, panOld = 3000f, zoomOld = 0.8f, zoomNew = 0.2f)
    }

    @Test
    fun fitZoomFitsWholeWorldForBigJournal() {
        val g = bigGeometry()
        val fit = g.fitZoom(viewportW, viewportH)
        assertTrue("fit должен быть в LOD2 для большого мира", fit < Lod.COMPACT_MIN_ZOOM)
        assertTrue(fit >= Lod.MIN_ZOOM_FLOOR)
        // Весь мир вместе с шапками помещается в тело.
        assertTrue(g.worldWidth * fit + g.dateColW(fit) <= viewportW + 0.5f)
        assertTrue(g.worldHeight * fit + g.headerH(fit) <= viewportH + 0.5f)
    }

    @Test
    fun fitZoomCapsAtOneForTinyJournal() {
        val g = tinyGeometry()
        assertEquals(1f, g.fitZoom(viewportW, viewportH), 0.001f)
    }

    @Test
    fun clampZoomRespectsFitAndMax() {
        val g = bigGeometry()
        val fit = g.fitZoom(viewportW, viewportH)
        assertEquals(Lod.MAX_ZOOM, g.clampZoom(10f, viewportW, viewportH), 0.001f)
        assertEquals(fit, g.clampZoom(0.0001f, viewportW, viewportH), 0.001f)
        assertEquals(1f, g.clampZoom(1f, viewportW, viewportH), 0.001f)
    }

    @Test
    fun visibleRowRangeTracksPanAndZoom() {
        val g = bigGeometry()
        assertEquals(0, g.firstVisibleRow(0f, 1f))
        // panY = 5 строк * rowH(152) = 760 → первая видимая строка 5.
        assertEquals(5, g.firstVisibleRow(760f, 1f))
        // На зуме 0.5 строка вдвое ниже (76px): 760 / 76 = 10.
        assertEquals(10, g.firstVisibleRow(760f, 0.5f))
    }

    private fun assertPivotStableX(g: MatrixGeometry, pivot: Float, panOld: Float, zoomOld: Float, zoomNew: Float) {
        val panNew = g.panXForZoom(pivot, panOld, zoomOld, zoomNew)
        val worldX = (pivot - g.dateColW(zoomOld) + panOld) / zoomOld
        val screenXNew = g.dateColW(zoomNew) + worldX * zoomNew - panNew
        assertEquals(pivot, screenXNew, 0.01f)
    }

    private fun assertPivotStableY(g: MatrixGeometry, pivot: Float, panOld: Float, zoomOld: Float, zoomNew: Float) {
        val panNew = g.panYForZoom(pivot, panOld, zoomOld, zoomNew)
        val worldY = (pivot - g.headerH(zoomOld) + panOld) / zoomOld
        val screenYNew = g.headerH(zoomNew) + worldY * zoomNew - panNew
        assertEquals(pivot, screenYNew, 0.01f)
    }
}
