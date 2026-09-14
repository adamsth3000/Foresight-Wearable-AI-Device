package com.foresight.gateway.vision.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs

class PointingRayResolverTest {
    private val resolver = PointingRayResolver()

    @Test
    fun `straight extended index finger is accepted with fingertip ray origin`() {
        val pointing = resolver.resolve(hand(indexPoints = listOf(
            point(0.40f, 0.70f), point(0.45f, 0.55f), point(0.50f, 0.40f), point(0.55f, 0.25f),
        )))

        assertNotNull(pointing)
        assertEquals(0.55f, pointing!!.origin.x, 0f)
        assertEquals(0.25f, pointing.origin.y, 0f)
        assertEquals(1f, length(pointing.direction), 0.0001f)
    }

    @Test
    fun `bent index finger is rejected`() {
        val pointing = resolver.resolve(hand(indexPoints = listOf(
            point(0.40f, 0.70f), point(0.45f, 0.55f), point(0.60f, 0.56f), point(0.62f, 0.42f),
        )))

        assertNull(pointing)
    }

    @Test
    fun `aspect correction preserves physical direction in normalized detection space`() {
        val pointing = resolver.resolve(hand(
            width = 200,
            height = 100,
            indexPoints = listOf(
                point(0.20f, 0.80f), point(0.30f, 0.70f), point(0.40f, 0.60f), point(0.50f, 0.50f),
            ),
        ))!!

        assertEquals(pointing.direction.x, -pointing.direction.y, 0.0001f)
    }

    @Test
    fun `zero length segment is rejected`() {
        assertNull(resolver.resolve(hand(indexPoints = listOf(
            point(0.40f, 0.70f), point(0.45f, 0.55f), point(0.45f, 0.55f), point(0.55f, 0.25f),
        ))))
    }

    @Test
    fun `mirrored left and right geometries are treated equivalently`() {
        val right = resolver.resolve(hand(indexPoints = listOf(
            point(0.30f, 0.70f), point(0.35f, 0.55f), point(0.40f, 0.40f), point(0.45f, 0.25f),
        )))!!
        val left = resolver.resolve(hand(handedness = Handedness.LEFT, indexPoints = listOf(
            point(0.70f, 0.70f), point(0.65f, 0.55f), point(0.60f, 0.40f), point(0.55f, 0.25f),
        )))!!

        assertEquals(abs(right.direction.x), abs(left.direction.x), 0.0001f)
        assertEquals(right.direction.y, left.direction.y, 0.0001f)
    }

    private fun hand(
        width: Int = 640,
        height: Int = 360,
        handedness: Handedness = Handedness.RIGHT,
        indexPoints: List<NormalizedHandLandmark>,
    ): HandObservation {
        val landmarks = MutableList(HandLandmarkIndex.COUNT) { point(0.5f, 0.5f) }
        landmarks[HandLandmarkIndex.INDEX_MCP] = indexPoints[0]
        landmarks[HandLandmarkIndex.INDEX_PIP] = indexPoints[1]
        landmarks[HandLandmarkIndex.INDEX_DIP] = indexPoints[2]
        landmarks[HandLandmarkIndex.INDEX_TIP] = indexPoints[3]
        return HandObservation(1, 1, 1, width, height, handedness, 0.9f, landmarks, 0.9f)
    }

    private fun point(x: Float, y: Float) = NormalizedHandLandmark(x, y)
    private fun length(vector: NormalizedImageVector): Float =
        kotlin.math.sqrt(vector.x * vector.x + vector.y * vector.y)
}
