package work.art1st.javarailways.core.physics.train

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.core.api.physics.WorldExecutionMode
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.RailView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class UniqueTrainPlacementTest {
    private class ProbeFinished : RuntimeException()

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `two physically fitting turnout branches are ambiguous independent of active selection`(selectOther: Boolean) = withWorld { world ->
        val stem = line(world, Vector3d(), Vector3d(100.0, 0.0, 0.0))
        val first = line(world, Vector3d(-100.0, 0.0, 0.0), Vector3d())
        val other = line(world, Vector3d(-100.0, 0.0, 5.0), Vector3d())
        world.onOwnerForTest {
            RailView.connect(first.viewAB, stem.viewBA); RailView.connect(other.viewAB, stem.viewBA)
            stem.viewBA.activeRail = if (selectOther) other.viewBA else null
        }
        probe(world, RailLocationWithFacing(stem.viewAB, 4.0)) { result ->
            assertInstanceOf(TrainPlacementResult.Ambiguous::class.java, result)
        }
        assertSame(if (selectOther) other.viewBA else null, stem.viewBA.activeRail)
    }

    @Test fun `one short branch leaves one full rigid placement and installs it exactly once`() = withWorld { world ->
        val stem = line(world, Vector3d(), Vector3d(100.0, 0.0, 0.0))
        val long = line(world, Vector3d(-100.0, 0.0, 0.0), Vector3d())
        val short = line(world, Vector3d(-1.0, 0.0, 0.0), Vector3d())
        world.onOwnerForTest {
            RailView.connect(long.viewAB, stem.viewBA); RailView.connect(short.viewAB, stem.viewBA)
            stem.viewBA.activeRail = short.viewBA
        }
        val train = install(world, RailLocationWithFacing(stem.viewAB, 4.0), listOf(false))
        assertEquals(4.0, train.publishedFrame.head.arcLengthInAbMeters, 1e-6)
        assertEquals(long.id, train.publishedFrame.tail.railId)
        assertEquals(94.0, train.publishedFrame.tail.arcLengthInAbMeters, 1e-6)
        assertEquals(listOf(long.id, stem.id), train.publishedFrame.bodyTraversal.map { it.railId })
        assertSame(long.viewBA, stem.viewBA.activeRail)
        assertEquals(1, world.allTrains.size)
    }

    @Test fun `fork seen by the axle search but beyond solved tail is one placement`() = withWorld { world ->
        val stem = line(world, Vector3d(), Vector3d(100.0, 0.0, 0.0))
        val a = line(world, Vector3d(-100.0, 0.0, 0.0), Vector3d())
        val b = line(world, Vector3d(-100.0, 0.0, 5.0), Vector3d())
        world.onOwnerForTest { RailView.connect(a.viewAB, stem.viewBA); RailView.connect(b.viewAB, stem.viewBA); stem.viewBA.activeRail = null }
        probe(world, RailLocationWithFacing(stem.viewAB, 12.0)) { result -> assertInstanceOf(TrainPlacementResult.Unique::class.java, result) }
        assertNull(stem.viewBA.activeRail)
        val train = install(world, RailLocationWithFacing(stem.viewAB, 12.0), listOf(false))
        assertEquals(2.0, train.publishedFrame.tail.arcLengthInAbMeters, 1e-6)
        assertEquals(listOf(stem.id), train.publishedFrame.bodyTraversal.map { it.railId })
        assertNull(stem.viewBA.activeRail, "no selected link is needed beyond the body")
    }

    @Test fun `nominally long folded branch cannot satisfy real axle chord and does not add a placement`() = withWorld { world ->
        val stem = line(world, Vector3d(), Vector3d(100.0, 0.0, 0.0))
        val long = line(world, Vector3d(-100.0, 0.0, 0.0), Vector3d())
        val folded = world.createRailForTest(PrimeCurve.fromPoints((0..128).map { index ->
            val angle = index * 2 * PI / 128
            Curve.RawPoint(Vector3d(-sin(angle), 0.0, 1 - cos(angle)),
                Vector3d(-cos(angle), 0.0, sin(angle)), Vector3d(0.0, 1.0, 0.0), angle)
        }))
        assertTrue(folded.curve.totalArcLength + 4.0 > 10.0, "arc length alone would wrongly claim fit")
        world.onOwnerForTest { RailView.connect(long.viewAB, stem.viewBA); RailView.connect(folded.viewAB, stem.viewBA) }
        val train = install(world, RailLocationWithFacing(stem.viewAB, 4.0), listOf(false))
        assertEquals(long.id, train.publishedFrame.tail.railId)
        val car = train.cars.single()
        assertEquals(6.0, car.bogieA.position.distance(car.bogieB.position), 1e-6)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `mixed physical fronts and sloped AB BA connections retain real asymmetric geometry`(headFacesBa: Boolean) = withWorld { world ->
        val rail = line(world, Vector3d(-100.0, -10.0, 0.0), Vector3d(100.0, 10.0, 0.0))
        val head = RailLocationWithFacing(if (headFacesBa) rail.viewBA else rail.viewAB, 100.0)
        val train = install(world, head, listOf(false, true), asymmetric = true)
        val before = train.publishedFrame
        train.cars.forEach { car ->
            val a = Vector3d(car.bogieA.position).add(Vector3d(car.bogieA.normal).mul(0.5))
            val b = Vector3d(car.bogieB.position).add(Vector3d(car.bogieB.normal).mul(0.5))
            assertEquals(5.0, a.distance(b), 1e-5)
            assertEquals(0.0, Vector3d(car.position).add(Vector3d(car.direction).mul(3.0)).distance(a), 1e-5)
            assertEquals(0.0, Vector3d(car.position).sub(Vector3d(car.direction).mul(2.0)).distance(b), 1e-5)
        }
        val first = train.cars[0]; val second = train.cars[1]
        val predecessorTail = Vector3d(first.position).sub(Vector3d(first.direction).mul(5.0))
        val followerHead = Vector3d(second.position).sub(Vector3d(second.direction).mul(5.0))
        assertEquals(1.6, predecessorTail.distance(followerHead), 1e-5)
        world.onOwnerForTest { train.reverse() }
        val after = train.publishedFrame
        assertEquals(before.cars.map { it.state }, after.cars.map { it.state }, "in-place reversal keeps physical car pose")
        assertEquals(before.head.railId, after.tail.railId)
        assertEquals(before.head.arcLengthInAbMeters, after.tail.arcLengthInAbMeters, 1e-9)
        assertEquals(before.tail.arcLengthInAbMeters, after.head.arcLengthInAbMeters, 1e-9)
        assertEquals(before.occupiedIntervals, after.occupiedIntervals)
    }

    @Test fun `local rail AB direction changes at a connected joint are preserved by the body proof`() = withWorld { world ->
        val stem = line(world, Vector3d(), Vector3d(100.0, 0.0, 0.0))
        val reversed = line(world, Vector3d(), Vector3d(-100.0, 0.0, 0.0))
        world.onOwnerForTest { RailView.connect(reversed.viewBA, stem.viewBA) }
        val train = install(world, RailLocationWithFacing(stem.viewAB, 4.0), listOf(false))
        assertEquals(listOf(ViewDirection.BA, ViewDirection.AB), train.publishedFrame.bodyTraversal.map { it.facing })
        assertEquals(6.0, train.publishedFrame.tail.arcLengthInAbMeters, 1e-6)
    }

    @Test fun `bounded incomplete exploration never accepts the first candidate as unique`() = withWorld { world ->
        val stem = line(world, Vector3d(), Vector3d(100.0, 0.0, 0.0))
        val a = line(world, Vector3d(-100.0, 0.0, 0.0), Vector3d())
        val b = line(world, Vector3d(-1.0, 0.0, 0.0), Vector3d())
        // The LIFO search proves the long branch first; an unchecked short branch must still block Unique.
        world.onOwnerForTest { RailView.connect(b.viewAB, stem.viewBA); RailView.connect(a.viewAB, stem.viewBA) }
        probe(world, RailLocationWithFacing(stem.viewAB, 4.0), budget = 2) { result ->
            assertInstanceOf(TrainPlacementResult.SearchLimit::class.java, result)
        }
    }

    @Test fun `dead end and disconnected nearby track cannot support a full body`() = withWorld { world ->
        val tiny = line(world, Vector3d(), Vector3d(5.0, 0.0, 0.0))
        line(world, Vector3d(-100.0, 0.0, 0.0), Vector3d())
        probe(world, RailLocationWithFacing(tiny.viewAB, 4.0)) { result -> assertInstanceOf(TrainPlacementResult.Unplaceable::class.java, result) }
    }

    @Test fun `prepared proof rejects post preparation car direction changes before train registration`() = withWorld { world ->
        val rail = line(world, Vector3d(), Vector3d(100.0, 0.0, 0.0))
        world.onOwnerForTest {
            assertThrows(IllegalStateException::class.java) {
                CoreTrain.createOwned(world) {
                    val head = RailLocationWithFacing(rail.viewAB, 50.0)
                    val cars = cars(this, head, listOf(false), false)
                    val prepared = (prepareUniquePlacement(cars, head) as TrainPlacementResult.Unique).placement
                    cars.single().reverse()
                    train("stale", cars, preparedPlacement = prepared)
                }
            }
        }
        assertTrue(world.allTrains.isEmpty()); assertTrue(rail.trains.isEmpty())
    }

    @Test fun `short loop placement retains two disjoint occurrences of its anchor rail`() = withWorld { world ->
        val loop = circularRail(world, 10.0)
        world.onOwnerForTest {
            RailView.connect(loop.viewAB, loop.viewBA)
            loop.viewAB.activeRail = null; loop.viewBA.activeRail = null
        }
        val train = install(world, RailLocationWithFacing(loop.viewAB, 2.0), listOf(false))
        val path = train.publishedFrame.bodyTraversal
        assertEquals(2, path.size)
        assertTrue(path.all { it.railId == loop.id && it.facing == ViewDirection.AB })
        assertTrue(path[0].startArcLengthInAbMeters > path[1].endArcLengthInAbMeters)
        assertEquals(loop.curve.totalArcLength, path[0].endArcLengthInAbMeters, 1e-9)
        assertEquals(0.0, path[1].startArcLengthInAbMeters, 1e-9)
        assertSame(loop.viewAB, loop.viewAB.activeRail)
        assertSame(loop.viewBA, loop.viewBA.activeRail)
        assertEquals(6.0, train.cars.single().bogieA.position.distance(train.cars.single().bogieB.position), Curve.TOLERANCE)
    }

    @Test fun `a consist longer than the complete loop cannot overlap its own occupied intervals`() = withWorld { world ->
        val loop = circularRail(world, 10.0)
        world.onOwnerForTest { RailView.connect(loop.viewAB, loop.viewBA) }
        probe(world, RailLocationWithFacing(loop.viewAB, 2.0), reversed = List(7) { false }) { result ->
            assertInstanceOf(TrainPlacementResult.Unplaceable::class.java, result)
        }
    }

    @Test fun `changes after train construction cannot publish an obsolete prepared placement`() = withWorld { world ->
        val rail = line(world, Vector3d(), Vector3d(100.0, 0.0, 0.0))
        world.onOwnerForTest {
            assertThrows(IllegalStateException::class.java) {
                CoreTrain.createOwned(world) {
                    val head = RailLocationWithFacing(rail.viewAB, 50.0)
                    val cars = cars(this, head, listOf(false), false)
                    val prepared = (prepareUniquePlacement(cars, head) as TrainPlacementResult.Unique).placement
                    train("changed", cars, preparedPlacement = prepared)
                    cars.single().reverse()
                    publish()
                }
            }
        }
        assertTrue(world.allTrains.isEmpty()); assertTrue(rail.trains.isEmpty())
    }

    private fun circularRail(world: CoreWorld, radius: Double): CoreRail =
        world.createRailForTest(PrimeCurve.fromPoints((0..1024).map { index ->
            val angle = index * 2 * PI / 1024
            Curve.RawPoint(Vector3d(-radius * sin(angle), 0.0, radius * (1 - cos(angle))),
                Vector3d(-cos(angle), 0.0, sin(angle)), Vector3d(0.0, 1.0, 0.0), radius * angle)
        }))

    private fun probe(world: CoreWorld, head: RailLocationWithFacing, budget: Int = 4096,
        reversed: List<Boolean> = listOf(false), verify: (TrainPlacementResult) -> Unit) {
        val topology = world.topologyState
        val selections = world.allRails.flatMap { listOf(it.viewAB, it.viewBA) }.associateWith { it.activeRail }
        world.onOwnerForTest {
            assertThrows(ProbeFinished::class.java) {
                CoreTrain.createOwned(world) {
                    val cars = cars(this, head, reversed, false)
                    val before = cars.map { it.captureActivePlacementFrame() }
                    val result = prepareUniquePlacement(cars, head, budget)
                    assertSame(topology, world.topologyState)
                    assertTrue(world.allTrains.isEmpty())
                    assertTrue(world.allRails.all { it.trains.isEmpty() })
                    assertEquals(before, cars.map { it.captureActivePlacementFrame() })
                    selections.forEach { (view, selected) -> assertSame(selected, view.activeRail) }
                    verify(result)
                    throw ProbeFinished()
                }
            }
        }
        assertTrue(world.allTrains.isEmpty()); assertTrue(world.allRails.all { it.trains.isEmpty() })
    }

    private fun install(world: CoreWorld, head: RailLocationWithFacing, reversed: List<Boolean>, asymmetric: Boolean = false): CoreTrain =
        world.onOwnerForTest {
            CoreTrain.createOwned(world) {
                val cars = cars(this, head, reversed, asymmetric)
                val result = prepareUniquePlacement(cars, head)
                assertInstanceOf(TrainPlacementResult.Unique::class.java, result)
                val prepared = (result as TrainPlacementResult.Unique).placement
                train("unique", cars, preparedPlacement = prepared).also { publish() }
            }
        }

    private fun cars(creation: TrainCreation, head: RailLocationWithFacing, reversed: List<Boolean>, asymmetric: Boolean): List<CoreCar> =
        reversed.mapIndexed { index, reverse ->
            creation.car("car-$index", creation.bogie(head), creation.bogie(head), length = 10.0,
                floorHeight = if (asymmetric) 0.5 else 0.0,
                connectorLengthAtA = if (asymmetric) 0.2 else 0.0,
                connectorLengthAtB = if (asymmetric) 0.8 else 0.0,
                bogieAOffset = if (asymmetric) 3.0 else null,
                bogieBOffset = if (asymmetric) -2.0 else null).also { if (reverse) it.reverse() }
        }

    private fun line(world: CoreWorld, from: Vector3d, to: Vector3d): CoreRail {
        val tangent = Vector3d(to).sub(from).normalize()
        return world.createRailForTest(PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(from, tangent, Vector3d(0.0, 1.0, 0.0), 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(to, tangent, Vector3d(0.0, 1.0, 0.0), from.distance(to), Curve.SegmentType.LINE_END)
        )))
    }

    private fun withWorld(block: (CoreWorld) -> Unit) {
        val world = createTestWorld(executionMode = WorldExecutionMode.SIMULATION)
        (world.tickManager as AutoCloseable).use { world.physicsCoordinator.use { block(world) } }
    }
}
