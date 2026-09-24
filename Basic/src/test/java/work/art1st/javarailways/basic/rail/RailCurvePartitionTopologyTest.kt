package work.art1st.javarailways.basic.rail

import work.art1st.javarailways.core.physics.createRailForTest

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.curve.Anchor
import work.art1st.javarailways.basic.curve.RailCurveBuildOptions
import work.art1st.javarailways.basic.curve.RailCurveFactory
import work.art1st.javarailways.basic.curve.RailCurvePartitioner
import work.art1st.javarailways.basic.curve.RailCurveTemplateType
import work.art1st.javarailways.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.util.annotation.Heavy
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.train.createTestWorld
import java.util.UUID

class RailCurvePartitionTopologyTest {
    @Test
    @OptIn(Heavy::class)
    fun `sub-tolerance logical part does not create a false fourth endpoint at isolated joint`() {
        val world = createTestWorld().also { TestBasicWorldDataRegistry.getOrCreate(it) }
        val oldStart = Vector3d(210.5, 71.0, -188.5)
        val joint = Vector3d(162.5, 71.0, -172.5)
        val oldTangent = Vector3d(-0.9486832980505138, 0.0, 0.31622776601683794)
        val old = world.createRailForTest(line(oldStart, joint, oldTangent))
        val result = RailCurveFactory.build(
            Anchor("saved-endpoint", joint, oldTangent),
            Anchor("new-endpoint", Vector3d(84.5, 70.0, -134.8), Vector3d(0.866, 0.0, -0.5)),
            RailCurveBuildOptions(template = RailCurveTemplateType.AUTO)
        )
        assertTrue(result.isSuccess, result.error)
        val curve = result.curve!!
        val observedLeadingTangentMeters = 4.662410280546975E-4
        val observedBreaks = listOf(
            observedLeadingTangentMeters / curve.totalArcLength,
            result.logicalBreakFractions[1]
        )
        val curves = RailCurvePartitioner.partitionFiveSegment(curve, observedBreaks).map { it.curve }
        val created = curves.map(world::createRailForTest)
        val topology = listOf(old) + created
        val checked = hashSetOf<Pair<UUID, UUID>>()

        created.forEach { first ->
            topology.forEach inner@{ second ->
                if (first === second) return@inner
                val ids = listOf(first.id, second.id).sortedBy(UUID::toString)
                if (!checked.add(ids[0] to ids[1])) return@inner
                val outcome = RailAutoConnector.connectIfAllowed(
                    first,
                    second,
                    RailAutoConnector.Options(endpointTolerance = Curve.TOLERANCE, topologyRails = topology)
                ).outcome
                assertNotEquals(RailAutoConnector.Outcome.TOO_MANY_ENDPOINTS, outcome)
            }
        }

        assertTrue(RailAutoConnector.areConnected(old, created.first()))
    }

    private fun line(from: Vector3d, to: Vector3d, tangent: Vector3d): PrimeCurve =
        PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(from), Vector3d(tangent), Vector3d(0.0, 1.0, 0.0), 0.0),
            Curve.RawPoint(Vector3d(to), Vector3d(tangent), Vector3d(0.0, 1.0, 0.0), from.distance(to))
        ))
}
