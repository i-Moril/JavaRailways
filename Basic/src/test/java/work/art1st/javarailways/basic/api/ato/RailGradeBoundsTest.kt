package work.art1st.javarailways.basic.api.ato

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import java.util.UUID

class RailGradeBoundsTest {
    @Test
    fun `oriented bounds include normalized interpolation extrema and round nonzero bounds down`() {
        val curve = curve(Vector3d(0.6, -0.8, 0.0), Vector3d(-0.6, -0.8, 0.0))

        // Both endpoints have grade -0.8, but their normalized midpoint points straight down.
        assertEquals(-1.0, RailGradeBounds.minimumGrade(curve))
        assertEquals(Math.nextDown(0.8), RailGradeBounds.minimumGrade(curve.reversed))
    }

    @Test
    fun `flat curves retain exact positive zero in either orientation`() {
        val curve = curve(Vector3d(2.0, 0.0, 0.0), Vector3d(3.0, 0.0, 0.0))

        assertEquals(0.0.toRawBits(), RailGradeBounds.minimumGrade(curve).toRawBits())
        assertEquals(0.0.toRawBits(), RailGradeBounds.minimumGrade(curve.reversed).toRawBits())
    }

    @Test
    fun `occupied traversal uses each facing and bounds whole curves beyond the occupied interval`() {
        val first = UUID(0L, 1L)
        val second = UUID(0L, 2L)
        val curve = curve(Vector3d(0.6, -0.8, 0.0), Vector3d(-0.6, -0.8, 0.0))
        val body = listOf(
            RailTraversalSegmentSnapshot(first, ViewDirection.AB, 0.0, 0.1),
            RailTraversalSegmentSnapshot(second, ViewDirection.BA, 1.0, 0.9)
        )
        val lookups = mutableListOf<Pair<UUID, ViewDirection>>()

        val grade = RailGradeBounds.mostAdverseOccupiedGrade(body) { railId, facing ->
            lookups += railId to facing
            if (facing == ViewDirection.AB) curve else curve.reversed
        }

        assertEquals(listOf(first to ViewDirection.AB, second to ViewDirection.BA), lookups)
        // The first occupied interval ends before the downhill extremum at 0.5 metres.
        assertEquals(-1.0, grade)
    }

    @Test
    fun `missing occupied evidence stays unavailable even after a valid curve`() {
        assertTrue(RailGradeBounds.mostAdverseOccupiedGrade(emptyList()) { _, _ ->
            error("empty traversal must not request geometry")
        }.isNaN())
        val first = UUID(0L, 1L)
        val missing = UUID(0L, 2L)
        val flat = curve(Vector3d(1.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0))
        val body = listOf(
            RailTraversalSegmentSnapshot(first, ViewDirection.AB, 0.0, 1.0),
            RailTraversalSegmentSnapshot(missing, ViewDirection.AB, 0.0, 1.0)
        )

        assertTrue(RailGradeBounds.mostAdverseOccupiedGrade(body) { railId, _ ->
            if (railId == first) flat else null
        }.isNaN())
    }

    private fun curve(firstTangent: Vector3d, lastTangent: Vector3d): PrimeCurve = PrimeCurve.fromPoints(listOf(
        Curve.RawPoint(Vector3d(0.0, 0.0, 0.0), firstTangent, Vector3d(0.0, 0.0, 1.0), 0.0),
        Curve.RawPoint(Vector3d(1.0, 0.0, 0.0), lastTangent, Vector3d(0.0, 0.0, 1.0), 1.0)
    ))
}
