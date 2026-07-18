package ru.papasheets.matrixgrid

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM-тесты единственной точки записи pan/zoom. [MatrixState.setTransform]/[MatrixState.panBy] обязаны
 * клампить значения в границы текущего geometry/вьюпорта (zoom → [fit..MAX], pan → границы с
 * центрированием), а до публикации вьюпорта — сохранять значения как есть.
 */
class MatrixStateTest {
    private val density = Density(2f)

    private val fields = listOf(
        GridField(id = "location", title = "Л", widthDp = 56, maxLines = 2, showAtCompactLod = true),
        GridField(id = "work", title = "ВИД РАБОТ", widthDp = 168, maxLines = 0, showAtCompactLod = false),
    )

    private fun bigGeometry() =
        MatrixGeometry(density, fields, groupCount = 28, rowMetrics = RowMetrics.uniform(density, 300))
    private val viewportW = 1080f
    private val viewportH = 2000f

    private fun stateWithViewport(): Pair<MatrixState, MatrixGeometry> {
        val g = bigGeometry()
        val s = MatrixState()
        s.updateViewport(g, viewportW, viewportH)
        return s to g
    }

    @Test
    fun setTransformClampsZoomAndPanIntoBounds() {
        val (s, g) = stateWithViewport()
        s.setTransform(zoom = 99f, panX = -1e6f, panY = 1e9f)
        assertEquals(Lod.MAX_ZOOM, s.zoom, 1e-3f)
        assertEquals(g.minPanX(viewportW, Lod.MAX_ZOOM), s.panX, 0.5f)
        assertEquals(g.maxPanY(viewportH, Lod.MAX_ZOOM), s.panY, 0.5f)
    }

    @Test
    fun setTransformClampsZoomUpToFit() {
        val (s, g) = stateWithViewport()
        s.setTransform(zoom = 0.0001f, panX = 0f, panY = 0f)
        assertEquals(g.fitZoom(viewportW, viewportH), s.zoom, 1e-3f)
    }

    @Test
    fun panByFollowsFingerAndClampsAtEdge() {
        val (s, g) = stateWithViewport()
        s.setTransform(zoom = 1f, panX = 500f, panY = 0f)
        s.panBy(dx = 100f, dy = 0f) // палец вправо на 100 → контент вправо → panX уменьшается
        assertEquals(400f, s.panX, 0.5f)
        s.panBy(dx = 1e6f, dy = 0f) // за левый край мира → нижняя граница pan
        assertEquals(g.minPanX(viewportW, 1f), s.panX, 0.5f)
    }

    @Test
    fun withoutViewportStoresValuesRaw() {
        val s = MatrixState() // updateViewport не вызывали — клампить нечем
        s.setTransform(zoom = 0.5f, panX = 123f, panY = 456f)
        assertEquals(0.5f, s.zoom, 1e-4f)
        assertEquals(123f, s.panX, 1e-4f)
        assertEquals(456f, s.panY, 1e-4f)
    }
}
