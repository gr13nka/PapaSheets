package ru.papasheets.matrixgrid

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-тесты чистой математики раскладки: клампинг pan (в т.ч. центрирование малого мира), выбор яруса,
 * hit-тест с учётом клампленных шапок на слабом зуме и инвариант пивота пинча. Плотность 2.0 выбрана
 * ради круглых пикселей: dp просто удваиваются.
 *
 * Набор подколонок «56 + 168 dp» — тот, что был захардкожен в матрице до перехода на [GridField];
 * тесты на нём служат регресс-гейтом: числа обязаны совпадать с дорефакторными.
 */
class MatrixGeometryTest {
    private val density = Density(2f)

    private fun field(id: String, widthDp: Int) =
        GridField(id = id, title = id, widthDp = widthDp, maxLines = 0, showAtCompactLod = true)

    /** Ровно те подколонки, что матрица знала захардкоженно: Л (56dp) и ВИД РАБОТ (168dp). */
    private val legacyFields = listOf(field("location", 56), field("work", 168))

    private fun geometry(
        rowCount: Int,
        groupCount: Int,
        fields: List<GridField> = legacyFields,
        rowMetrics: RowMetrics = RowMetrics.uniform(density, rowCount),
    ) = MatrixGeometry(density, fields, groupCount, rowMetrics)

    // Большой мир (как «Январь 2000»): 28 групп × 300 строк.
    private fun bigGeometry() = geometry(rowCount = 300, groupCount = 28)

    // Мир меньше вьюпорта — для проверки центрирования.
    private fun tinyGeometry() = geometry(rowCount = 1, groupCount = 1)

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

    // --- Подколонки (ось X). ---

    @Test
    fun legacyFieldsReproducePreRefactorColumnMetrics() {
        // Регресс-гейт: до рефакторинга photoColW=144, locColW=112, workColW=336, groupW=592 (density 2).
        val g = bigGeometry()
        assertEquals(144f, g.photoColW, 0.01f)
        assertEquals(144f, g.fieldLeft(0), 0.01f)
        assertEquals(112f, g.fieldWidth(0), 0.01f)
        assertEquals(256f, g.fieldLeft(1), 0.01f)
        assertEquals(336f, g.fieldWidth(1), 0.01f)
        assertEquals(592f, g.groupW, 0.01f)
        assertEquals(592f * 28, g.worldWidth, 0.01f)
    }

    @Test
    fun fieldOffsetsSpanExactlyTheGroupForAnyFieldCount() {
        for (count in intArrayOf(0, 1, 2, 8)) {
            val fields = List(count) { field("f$it", widthDp = 40 + it * 8) }
            val g = geometry(rowCount = 5, groupCount = 3, fields = fields)

            assertEquals("count=$count", count, g.fieldCount)
            // Первое поле начинается сразу за Ф, последняя граница совпадает с правым краем группы.
            assertEquals("count=$count", g.photoColW, g.fieldLeft(0), 0.01f)
            assertEquals("count=$count", g.groupW, g.fieldLeft(count), 0.01f)

            var sum = 0f
            for (i in 0 until count) {
                assertTrue("count=$count", g.fieldWidth(i) > 0f)
                assertEquals("count=$count", g.fieldLeft(i) + g.fieldWidth(i), g.fieldLeft(i + 1), 0.01f)
                sum += g.fieldWidth(i)
            }
            assertEquals("count=$count", g.groupW - g.photoColW, sum, 0.01f)
        }
    }

    @Test
    fun groupWidthIsConstantSoGroupLookupStaysUniform() {
        // Инвариант оси X: подколонки одни и те же во всех группах, поэтому шаг групп постоянен и
        // «координата → группа» остаётся делением. Проверяем, что шаг действительно не плавает.
        val g = bigGeometry()
        val zoom = 1f
        val step = g.groupScreenLeft(1, 0f, zoom) - g.groupScreenLeft(0, 0f, zoom)
        for (group in 0 until 27) {
            val actual = g.groupScreenLeft(group + 1, 0f, zoom) - g.groupScreenLeft(group, 0f, zoom)
            assertEquals("group=$group", step, actual, 0.01f)
        }
        assertEquals(g.groupW * zoom, step, 0.01f)
    }

    @Test
    fun emptyFieldListLeavesPhotoOnlyGroupWithoutDegenerateWorld() {
        val g = geometry(rowCount = 4, groupCount = 3, fields = emptyList())
        assertEquals(g.photoColW, g.groupW, 0.01f)
        assertTrue(g.worldWidth > 0f)

        val fit = g.fitZoom(viewportW, viewportH)
        assertFalse(fit.isNaN())
        assertTrue(fit > 0f)
        assertFalse(g.clampPanX(0f, viewportW, fit).isNaN())
        assertFalse(g.clampPanY(0f, viewportH, fit).isNaN())
    }

    @Test
    fun emptyWorldDoesNotDivideByZero() {
        val g = geometry(rowCount = 0, groupCount = 0, fields = emptyList())
        val fit = g.fitZoom(viewportW, viewportH)
        assertFalse(fit.isNaN())
        assertEquals(1f, fit, 0.001f)
        assertFalse(g.clampPanY(100f, viewportH, fit).isNaN())
        assertFalse(g.clampPanX(100f, viewportW, fit).isNaN())
        assertTrue(g.hitTest(500f, 500f, 0f, 0f, fit) is MatrixHit.Other)
    }

    // --- Метрики строк (ось Y). ---

    @Test
    fun uniformAndVariableAgreeWhenRowsAreEqual() {
        // Быстрый путь (деление) и общий (бинарный поиск) обязаны отвечать одинаково — иначе рендерер
        // и hit-тест разъедутся ровно на переходе журнала к автовысоте.
        val rowH = 152f
        val count = 40
        val uniform = RowMetrics.Uniform(count, rowH)
        val variable = RowMetrics.Variable(FloatArray(count + 1) { it * rowH })

        assertEquals(uniform.totalHeight, variable.totalHeight, 0.01f)
        for (row in 0 until count) {
            assertEquals("row=$row", uniform.topOf(row), variable.topOf(row), 0.01f)
            assertEquals("row=$row", uniform.heightOf(row), variable.heightOf(row), 0.01f)
        }
        for (y in intArrayOf(-100, 0, 1, 151, 152, 153, 3000, 6080, 99999)) {
            assertEquals("y=$y", uniform.rowAt(y.toFloat()), variable.rowAt(y.toFloat()))
        }
    }

    @Test
    fun variableRowLookupIsInverseOfRowTop() {
        val heights = floatArrayOf(152f, 300f, 152f, 90f, 512f)
        val metrics = RowMetrics.of(heights, baseHeight = 152f)
        assertTrue("разные высоты обязаны дать Variable", metrics is RowMetrics.Variable)
        assertEquals(heights.sum(), metrics.totalHeight, 0.01f)

        for (row in heights.indices) {
            val top = metrics.topOf(row)
            assertEquals("верх строки row=$row", row, metrics.rowAt(top))
            assertEquals("середина row=$row", row, metrics.rowAt(top + metrics.heightOf(row) / 2f))
            // Точка вплотную под верхней границей принадлежит уже ПРЕДЫДУЩЕЙ строке.
            if (row > 0) assertEquals("граница row=$row", row - 1, metrics.rowAt(top - 0.01f))
        }
        assertEquals(0, metrics.rowAt(-500f))
        assertEquals(heights.size - 1, metrics.rowAt(metrics.totalHeight))
        assertEquals(heights.size - 1, metrics.rowAt(metrics.totalHeight + 1000f))
    }

    @Test
    fun equalHeightsCollapseToTheFastPath() {
        val metrics = RowMetrics.of(FloatArray(12) { 152f }, baseHeight = 152f)
        assertTrue("без разброса высот нужен быстрый путь", metrics is RowMetrics.Uniform)
    }

    @Test
    fun overviewTiersKeepTheFlatGridWhileDetailTierGrowsRows() {
        // Строки растянуты под текст только на LOD0; «картина месяца» обязана остаться ровной сеткой.
        val rowH = 152f
        val tall = RowMetrics.of(floatArrayOf(rowH, 600f, rowH), baseHeight = rowH)
        val g = geometry(rowCount = 3, groupCount = 2, rowMetrics = tall)

        assertEquals(tall.totalHeight, g.worldHeight(1f), 0.01f)
        assertEquals(600f, g.rowHeight(1, 1f), 0.01f)

        val compact = Lod.COMPACT_MIN_ZOOM
        assertEquals(rowH * 3, g.worldHeight(compact), 0.01f)
        assertEquals(rowH, g.rowHeight(1, compact), 0.01f)
        // Ровная сетка: третья строка начинается ровно через две базовые высоты, а не за растянутой.
        assertEquals(
            g.headerH(compact) + rowH * 2 * compact,
            g.rowScreenTop(2, 0f, compact),
            0.01f,
        )
    }

    @Test
    fun panClampsUseTheMetricsOfTheirOwnTier() {
        // На пороге LOD0↔LOD1 высота мира меняется скачком: кламп обязан считаться по метрикам того
        // яруса, на котором кадр рисуется, иначе прокрутка прыгнет при пересечении порога.
        val rowH = 152f
        val tall = RowMetrics.of(FloatArray(60) { if (it % 2 == 0) rowH else 600f }, baseHeight = rowH)
        val g = geometry(rowCount = 60, groupCount = 4, rowMetrics = tall)

        val detail = Lod.DETAIL_MIN_ZOOM
        val compact = Lod.DETAIL_MIN_ZOOM - 0.01f
        assertEquals(tall.totalHeight * detail - g.bodyHeight(viewportH, detail), g.maxPanY(viewportH, detail), 0.01f)
        assertEquals(rowH * 60 * compact - g.bodyHeight(viewportH, compact), g.maxPanY(viewportH, compact), 0.01f)
        assertTrue("детальный мир выше обзорного", g.worldHeight(detail) > g.worldHeight(compact))
    }

    @Test
    fun pinchAcrossLodThresholdKeepsTheSameRowUnderTheFinger() {
        val rowH = 152f
        val tall = RowMetrics.of(FloatArray(60) { if (it % 2 == 0) rowH else 600f }, baseHeight = rowH)
        val g = geometry(rowCount = 60, groupCount = 4, rowMetrics = tall)

        val zoomOld = Lod.DETAIL_MIN_ZOOM // LOD0, строки растянуты
        val zoomNew = Lod.DETAIL_MIN_ZOOM - 0.01f // LOD1, ровная сетка
        val pivotY = 900f
        val panOld = 4000f

        val rowBefore = g.hitTest(600f, pivotY, 0f, panOld, zoomOld)
        val panNew = g.panYForZoom(pivotY, panOld, zoomOld, zoomNew)
        val rowAfter = g.hitTest(600f, pivotY, 0f, panNew, zoomNew)

        assertTrue(rowBefore is MatrixHit.Body && rowAfter is MatrixHit.Body)
        assertEquals((rowBefore as MatrixHit.Body).row, (rowAfter as MatrixHit.Body).row)
    }

    // --- Дорефакторные проверки: числа не должны сдвинуться. ---

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
        val cy = g.rowScreenTop(0, panY, zoom) + g.rowHeight(0, zoom) * zoom / 2f

        val hit = g.hitTest(cx, cy, panX, panY, zoom) as MatrixHit.Body
        assertEquals(0, hit.group)
        assertEquals(0, hit.row)
        // Левее нарисованной группы — поле центрирования, не тело.
        assertTrue(g.hitTest(g.groupScreenLeft(0, panX, zoom) - 5f, cy, panX, panY, zoom) is MatrixHit.Other)
    }

    @Test
    fun hitTestAgreesWithRendererOnVariableRows() {
        // То же согласование, но на растянутых строках: середина каждой нарисованной строки обязана
        // попадать в неё же по hit-тесту.
        val rowH = 152f
        val tall = RowMetrics.of(floatArrayOf(rowH, 600f, 240f, rowH), baseHeight = rowH)
        val g = geometry(rowCount = 4, groupCount = 2, rowMetrics = tall)
        val zoom = 1f
        val panX = g.clampPanX(0f, viewportW, zoom)
        val panY = g.clampPanY(0f, viewportH, zoom)
        val cx = g.groupScreenLeft(1, panX, zoom) + g.groupW * zoom / 2f

        for (row in 0 until 4) {
            val cy = g.rowScreenTop(row, panY, zoom) + g.rowHeight(row, zoom) * zoom / 2f
            val hit = g.hitTest(cx, cy, panX, panY, zoom) as MatrixHit.Body
            assertEquals("row=$row", row, hit.row)
            assertEquals("row=$row", 1, hit.group)
        }
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
        assertTrue(g.worldHeight(fit) * fit + g.headerH(fit) <= viewportH + 0.5f)
    }

    @Test
    fun fitZoomCapsAtOneForTinyJournal() {
        val g = tinyGeometry()
        assertEquals(1f, g.fitZoom(viewportW, viewportH), 0.001f)
    }

    @Test
    fun fitZoomMeasuresTheTierItLandsOn() {
        // Мир, который на ровной сетке влез бы целиком, а с растянутыми строками — нет. fit обязан
        // считаться по метрикам того яруса, на который он сам попадает.
        val rowH = 152f
        val tall = RowMetrics.of(FloatArray(20) { 800f }, baseHeight = rowH)
        val g = geometry(rowCount = 20, groupCount = 2, rowMetrics = tall)

        val fit = g.fitZoom(viewportW, viewportH)
        assertFalse(fit.isNaN())
        assertTrue(fit in Lod.MIN_ZOOM_FLOOR..1f)
        assertTrue(g.worldHeight(fit) * fit + g.headerH(fit) <= viewportH + 0.5f)
    }

    @Test
    fun fitZoomNeverLandsOnTierWhereWorldNoLongerFits() {
        // Короткий журнал с длинными текстами: ровная сетка (8 × 152) влезает даже на 1.0, а
        // растянутая (8 × 500) не влезает нигде в LOD0. Наивный ответ «раз ровный мир влезает на 1.0,
        // fit = 1.0» заперал бы зум на 1.0 — а это нижняя граница clampZoom, то есть зум-аут и кнопка
        // «Месяц» переставали бы работать вовсе.
        val tall = RowMetrics.of(FloatArray(8) { 500f }, baseHeight = 152f)
        val g = geometry(rowCount = 8, groupCount = 1, rowMetrics = tall)

        val fit = g.fitZoom(viewportW, viewportH)

        assertTrue("fit обязан остаться ниже порога LOD0, иначе зум-аут заперт", fit < Lod.DETAIL_MIN_ZOOM)
        assertTrue("на своём же ярусе мир обязан помещаться", g.worldHeight(fit) * fit + g.headerH(fit) <= viewportH + 0.5f)
        assertTrue("зум-аут до fit должен быть разрешён", g.clampZoom(fit, viewportW, viewportH) == fit)
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
