package work.art1st.javarailways.simpleato.timetable

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.route.planning.DirectedRailSpan
import work.art1st.javarailways.basic.api.route.planning.PathReplacement
import work.art1st.javarailways.basic.api.route.planning.PhysicalTraversalPath
import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictions
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.*
import java.util.concurrent.TimeUnit

@OptIn(work.art1st.javarailways.core.api.util.annotation.AsyncEventApi::class)
class StructuralOutputCorridorTest {
    @ParameterizedTest
    @CsvSource("false,false,false", "false,false,true", "false,true,false", "false,true,true",
        "true,false,false", "true,false,true", "true,true,false", "true,true,true")
    fun `real straight and curved splits resolve both physical halves in either direction`(
        curved: Boolean, sourceReversed: Boolean, outputReversed: Boolean
    ) = TestWorldFixture.create().use { fixture ->
        val world = fixture.world
        val rail = createStraightRailFromSegments(world, if (curved)
            PrimeCurveGenerator.buildCircle(100.0, 4).take(1) else PrimeCurveGenerator.buildStraight(160.0, 1))
        val train = createTrainWithConfig("source", rail, world, List(4) { 10.0 }, connectorLength = 0.25)
        world.onOwnerForTest {
            train.setLocation(rail.viewAB.getRailLocationAt(100.0))
            train.commit()
            if (sourceReversed) train.reverse()
        }
        val initial = train.publishedFrame
        val topology = world.topologyState
        val railLength = rail.curve.totalArcLength
        fun segment(from: Double, to: Double, direction: ViewDirection) = RouteSegmentSnapshot(rail.id, direction,
            if (direction == ViewDirection.AB) from else railLength - from,
            if (direction == ViewDirection.AB) to else railLength - to)
        val sign = if (initial.head.facing == ViewDirection.AB) 1.0 else -1.0
        val maximumHead = initial.head.arcLengthInAbMeters + sign * 5.0
        val incoming = RouteSnapshot("incoming", 0, topology.revision,
            listOf(segment(initial.head.arcLengthInAbMeters, maximumHead, initial.head.facing)))
        val reverse = outputReversed != sourceReversed
        val facing = if (outputReversed) ViewDirection.BA else ViewDirection.AB
        val start = if (reverse) maximumHead else initial.tail.arcLengthInAbMeters
        val corridorEnd = if (reverse) initial.tail.arcLengthInAbMeters else maximumHead
        val target = corridorEnd + if (facing == ViewDirection.AB) 20.0 else -20.0
        val onward = RouteSnapshot("onward", 4, topology.revision, listOf(segment(start, target, facing)))
        val proof = StructuralOutputCorridor.prove(initial, incoming, onward, topology,
            RoutePlanningRestrictions.unrestricted(topology), reverse)
        // Use actual Core geometry at a later position inside the retained incoming stop range.
        world.onOwnerForTest {
            train.moveTrainForward(2.0)
            train.commit()
        }
        val future = train.splitBefore("car-2", "second")
        world.onOwnerForTest { world.physicsCoordinator.drainBetweenFramesOperations() }
        val second = future.get(5, TimeUnit.SECONDS) as CoreTrain
        for (output in listOf(train, second)) {
            world.onOwnerForTest { if (output.isReversed != outputReversed) output.reverse() }
            val frame = output.publishedFrame
            val resolved = proof.resolve(frame, topology, output.cars.map { it.id }, outputReversed)
            assertTrue(resolved.headMeters > resolved.tailMeters)
            assertEquals(onward.totalLengthMeters - resolved.headMeters, resolved.movement.totalLengthMeters, 1e-6)
            assertEquals(onward.totalLengthMeters - resolved.tailMeters, resolved.admission.totalLengthMeters, 1e-6)
            val first = resolved.admission.segments.first()
            val canonicalStart = if (facing == ViewDirection.AB) first.startArcLengthMeters else railLength - first.startArcLengthMeters
            assertEquals(frame.tail.arcLengthInAbMeters, canonicalStart, 1e-6)
            assertEquals(onward.segments.last().endArcLengthMeters, resolved.admission.segments.last().endArcLengthMeters, 1e-6)
            assertThrows(IllegalArgumentException::class.java) {
                proof.resolve(frame, topology, listOf("wrong-car"), outputReversed)
            }
            assertThrows(IllegalArgumentException::class.java) {
                proof.resolve(frame, topology, output.cars.map { it.id }, !outputReversed)
            }
            val incompleteBody = withBody(frame, emptyList())
            assertThrows(IllegalArgumentException::class.java) {
                proof.resolve(incompleteBody, topology, output.cars.map { it.id }, outputReversed)
            }
            world.onOwnerForTest {
                val outside = corridorEnd + if (facing == ViewDirection.AB) 10.0 else -10.0
                output.setLocation(if (facing == ViewDirection.AB) rail.viewAB.getRailLocationAt(outside)
                    else rail.viewBA.getRailLocationAt(railLength - outside))
                output.commit()
            }
            assertThrows(IllegalArgumentException::class.java) {
                proof.resolve(output.publishedFrame, topology, output.cars.map { it.id }, outputReversed)
            }
        }
    }

    @Test
    fun `rejects absent prefix target inside uncertainty and changed mandatory path`() = withStraight { topology, rail, initial ->
        fun route(from: Double, to: Double) = RouteSnapshot("selected", 0, topology.revision,
            listOf(RouteSegmentSnapshot(rail, ViewDirection.AB, from, to)))
        fun prove(source: TrainFrameSnapshot = initial,
            incoming: RouteSnapshot = route(30.0, 40.0),
            onward: RouteSnapshot = route(10.0, 70.0), offset: Double = 0.0) =
            StructuralOutputCorridor.prove(source, incoming, onward, topology, RoutePlanningRestrictions.unrestricted(topology),
                false, offset)
        val absentBody = withBody(initial, emptyList())
        assertThrows(IllegalArgumentException::class.java) { prove(source = absentBody) }
        val partial = withBody(initial, initial.bodyTraversal.map { it.copy(startArcLengthInAbMeters = 11.0) })
        assertThrows(IllegalArgumentException::class.java) { prove(source = partial) }
        assertThrows(IllegalArgumentException::class.java) { prove(incoming = route(31.0, 40.0)) }
        assertThrows(IllegalArgumentException::class.java) { prove(onward = route(11.0, 70.0)) }
        assertThrows(IllegalArgumentException::class.java) { prove(offset = 31.0) }
        assertThrows(IllegalArgumentException::class.java) { prove(offset = Double.NaN) }
        assertNotNull(prove())
    }

    @Test
    fun `atomic replacement must be complete and must not contain uncertain origins`() = withStraight { topology, rail, initial ->
        fun span(a: Double, b: Double) = DirectedRailSpan(rail, ViewDirection.AB, a, b)
        fun route(a: Double, b: Double) = RouteSnapshot("selected", 0, topology.revision,
            listOf(RouteSegmentSnapshot(rail, ViewDirection.AB, a, b)))
        fun prove(replacement: PathReplacement) = StructuralOutputCorridor.prove(initial,
            route(30.0, 40.0), route(10.0, 90.0), topology,
            RoutePlanningRestrictions(topology.revision, pathReplacements = listOf(replacement)),
            false)
        val uncertain = PathReplacement(listOf(span(20.0, 50.0)), listOf(PhysicalTraversalPath(listOf(span(20.0, 50.0)))))
        assertThrows(IllegalArgumentException::class.java) { prove(uncertain) }
        val ahead = PathReplacement(listOf(span(50.0, 70.0)), listOf(PhysicalTraversalPath(listOf(span(50.0, 70.0)))))
        assertNotNull(prove(ahead))
        val incomplete = PathReplacement(listOf(span(50.0, 70.0)), listOf(PhysicalTraversalPath(listOf(span(50.0, 100.0)))))
        assertThrows(IllegalArgumentException::class.java) { prove(incomplete) }
    }

    @Test
    fun `adjacent corridor seams retain one occurrence despite edge coordinate roundoff`() = withStraight { topology, rail, initial ->
        val head = initial.head.arcLengthInAbMeters
        val seam = head + work.art1st.javarailways.basic.api.ato.AtoSpatialPolicy.POSITION_TOLERANCE_METERS
        val incoming = RouteSnapshot("decimal-origin", 0, topology.revision,
            listOf(RouteSegmentSnapshot(rail, ViewDirection.AB, head, seam)))
        // A route edge containing several physical rails may reconstruct a local endpoint
        // through subtraction from the far end. Test both gap and overlap rounding directions.
        for (rounded in listOf(Math.nextUp(seam), Math.nextDown(seam))) {
            assertNotEquals(seam, rounded)
            val onward = RouteSnapshot("decimal-onward", 0, topology.revision, listOf(
                RouteSegmentSnapshot(rail, ViewDirection.AB, initial.tail.arcLengthInAbMeters, seam),
                RouteSegmentSnapshot(rail, ViewDirection.AB, rounded, 90.0)))
            val proof = StructuralOutputCorridor.prove(initial, incoming, onward, topology,
                RoutePlanningRestrictions.unrestricted(topology), false)
            val resolved = proof.resolve(initial, topology, initial.cars.map { it.carId }, initial.reversed)
            assertEquals(90.0 - head, resolved.movement.totalLengthMeters, 1e-12)
            assertEquals(90.0 - initial.tail.arcLengthInAbMeters, resolved.admission.totalLengthMeters, 1e-12)
        }
    }

    @Test
    fun `rejects duplicated traversal and forbidden direction`() = withStraight { topology, rail, initial ->
        fun route(segments: List<RouteSegmentSnapshot>) = RouteSnapshot("selected", 0, topology.revision, segments)
        val incoming = route(listOf(RouteSegmentSnapshot(rail, ViewDirection.AB, 30.0, 40.0)))
        val normal = route(listOf(RouteSegmentSnapshot(rail, ViewDirection.AB, 10.0, 70.0)))
        val duplicated = route(normal.segments + RouteSegmentSnapshot(rail, ViewDirection.BA, 90.0, 110.0))
        for ((onward, restrictions) in listOf(duplicated to RoutePlanningRestrictions.unrestricted(topology),
            normal to RoutePlanningRestrictions(topology.revision, listOf(DirectedRailSpan(rail, ViewDirection.AB, 20.0, 25.0))))) {
            assertThrows(IllegalArgumentException::class.java) {
                StructuralOutputCorridor.prove(initial, incoming, onward, topology, restrictions, false)
            }
        }
    }

    @Test
    fun `certified child envelope preserves ancestry full rear and reorientation without invented frames`(): Unit =
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(160.0, 1))
            val train = createTrainWithConfig("ancestor", rail, world, listOf(10.0, 10.0))
            val initial = train.publishedFrame
            val topology = world.topologyState
            fun route(a: Double, b: Double) = RouteSnapshot("retained", 3, topology.revision,
                listOf(if (b > a) RouteSegmentSnapshot(rail.id, ViewDirection.AB, a, b)
                    else RouteSegmentSnapshot(rail.id, ViewDirection.BA, 160.0 - a, 160.0 - b)))
            val restrictions = RoutePlanningRestrictions.unrestricted(topology)
            val root = CertifiedStructuralCorridor.fromInitial(initial, route(30.0, 40.0), topology)
            val first = StructuralOutputCorridor.prove(root, route(10.0, 70.0), restrictions, false)
            val future = train.splitBefore("car-1", "child")
            world.onOwnerForTest { world.physicsCoordinator.drainBetweenFramesOperations() }
            val separated = future.get(5, TimeUnit.SECONDS) as CoreTrain
            val child = train // The forward output can move onward while the other half remains behind.
            val memberIds = child.cars.map { it.id }
            val immediate = root.splitOutput(memberIds)
            assertSame(root.retainedPath, immediate.retainedPath,
                "a held child inherits the whole source path before any onward movement is chosen")
            immediate.requireContains(child.publishedFrame, topology, memberIds, false)
            assertThrows(IllegalArgumentException::class.java) { root.splitOutput(root.memberCarIds) }
            assertThrows(IllegalArgumentException::class.java) { root.splitOutput(listOf("unknown")) }
            val envelope = first.outputEnvelope(memberIds)
            assertSame(initial, envelope.initialFrames.single())
            assertSame(topology, envelope.topology)
            assertEquals(10.0, envelope.retainedPath.segments.first().startArcLengthMeters)
            assertEquals(70.0, envelope.retainedPath.segments.last().endArcLengthMeters)
            assertEquals(memberIds, envelope.memberCarIds)
            assertThrows(UnsupportedOperationException::class.java) {
                (envelope.memberCarIds as MutableList<String>).clear()
            }
            envelope.requireContains(child.publishedFrame, topology, memberIds, false)
            assertThrows(IllegalArgumentException::class.java) {
                first.outputEnvelope(listOf("unrelated-car"))
            }
            assertThrows(IllegalArgumentException::class.java) {
                first.outputEnvelope(initial.cars.map { it.carId }.asReversed())
            }
            world.onOwnerForTest { child.moveTrainForward(35.0); child.commit() }
            envelope.requireContains(child.publishedFrame, topology, memberIds, false)
            val next = StructuralOutputCorridor.prove(envelope, route(10.0, 100.0), restrictions, false)
                .outputEnvelope(memberIds)
            assertEquals(10.0, next.retainedPath.segments.first().startArcLengthMeters,
                "forward travel never justifies trimming the uncertain rear")
            val backwards = envelope.reoriented(true)
            world.onOwnerForTest { child.reverse() }
            backwards.requireContains(child.publishedFrame, topology, memberIds, true)
            assertTrue(backwards.reversed)
            assertSame(backwards, backwards.reoriented(false))
            val reverseProof = StructuralOutputCorridor.prove(backwards, route(70.0, 0.0), restrictions, false)
            assertTrue(reverseProof.resolve(child.publishedFrame, topology, memberIds, true).movement.totalLengthMeters > 0.0)
            assertThrows(IllegalArgumentException::class.java) {
                StructuralOutputCorridor.prove(backwards, route(70.0, 30.0), restrictions, false)
            }
            assertThrows(IllegalArgumentException::class.java) {
                envelope.requireContains(child.publishedFrame, topology, memberIds, false)
            }
            assertThrows(IllegalArgumentException::class.java) {
                backwards.requireContains(separated.publishedFrame, topology, separated.cars.map { it.id }, false)
            }
        }

    private fun withBody(frame: TrainFrameSnapshot, body: List<RailTraversalSegmentSnapshot>) = TrainFrameSnapshot.create(
        frame.trainId, frame.trainName, frame.revision, frame.physicsEpoch, frame.speedMetersPerSecond,
        frame.derailed, frame.reversed, frame.cars, frame.head, frame.tail, frame.occupiedIntervals,
        body, frame.sweptSpans, frame.headTraversal, frame.tailTraversal)

    private fun withStraight(test: (work.art1st.javarailways.core.api.physics.rail.topology.TopologyState, java.util.UUID,
        TrainFrameSnapshot) -> Unit) =
        TestWorldFixture.create().use { fixture ->
            val rail = createStraightRailFromSegments(fixture.world, PrimeCurveGenerator.buildStraight(160.0, 1))
            val source = createTrainWithConfig("source", rail, fixture.world, listOf(10.0, 10.0))
            test(fixture.world.topologyState, rail.id, source.publishedFrame)
        }
}
