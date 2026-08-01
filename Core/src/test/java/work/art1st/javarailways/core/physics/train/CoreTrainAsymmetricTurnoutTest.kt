package work.art1st.javarailways.core.physics.train

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.exception.CannotCreateTrainException
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.util.AsyncTickTestSupport

class CoreTrainAsymmetricTurnoutTest {
    @Test
    fun `removing an active rail selects the first remaining candidate even in invalid multi-branch topology`() {
        val world = createTestWorld()
        val stem = rail(world, Vector3d(-10.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0))
        val first = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0))
        val second = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 5.0))
        val removed = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, -5.0))
        CoreRail.connect(stem, first)
        CoreRail.connect(stem, second)
        CoreRail.connect(stem, removed)
        stem.viewAB.activeRail = removed.viewAB

        world.removeRail(removed)

        assertEquals(listOf(first.viewAB, second.viewAB), stem.viewAB.mutableNextRails)
        assertTrue(stem.viewAB.activeRail === first.viewAB)
    }

    @Test
    fun `loading after active target disappeared selects first surviving candidate`() {
        val sourceWorld = createTestWorld()
        val stem = rail(sourceWorld, Vector3d(-10.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0))
        val removed = rail(sourceWorld, Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, -5.0))
        val first = rail(sourceWorld, Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0))
        val second = rail(sourceWorld, Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 5.0))
        CoreRail.connect(stem, removed)
        CoreRail.connect(stem, first)
        CoreRail.connect(stem, second)
        stem.viewAB.activeRail = removed.viewAB
        val saved = sourceWorld.allRails.filter { it !== removed }.map(CoreRail::save)

        val restoredWorld = createTestWorld()
        val dependency = CoreRail.SavedRailV1.Dependency(restoredWorld)
        val restored = saved.associateWith { it.loadDisconnected(dependency) }
        restored.forEach { (snapshot, rail) -> snapshot.restoreConnections(rail, dependency) }
        val restoredStem = restoredWorld.getRail(stem.id)!!

        assertEquals(listOf(first.id, second.id), restoredStem.viewAB.nextRails.map { it.rail.id })
        assertEquals(first.id, restoredStem.viewAB.activeRail?.rail?.id)
    }

    @Test
    fun `moving a long train uses its own tail path when reverse active path is unset`() {
        val fixture = fixtureWithTailAcrossJunction()
        fixture.junction.viewBA.activeRail = null

        AsyncTickTestSupport.run { fixture.train.moveTrainForward(0.5) }

        assertFalse(fixture.train.isDerailed, fixture.train.derailReason)
    }

    @Test
    fun `tail endpoint is re-anchored without accumulating head delta on repeated ticks`() {
        val fixture = fixtureWithTailAcrossJunction()
        fixture.junction.viewBA.activeRail = null

        repeat(80) {
            AsyncTickTestSupport.run { fixture.train.moveTrainForward(0.1) }
            assertFalse(fixture.train.isDerailed, fixture.train.derailReason)
            val projectedBogie = fixture.train.tail.location.forward(
                fixture.train.tailCar.tailBogieToEndPointOffset
            )
            assertTrue(projectedBogie != null)
            assertTrue(
                projectedBogie!!.position.distance(fixture.train.tailBogie.location.position) <= Curve.TOLERANCE,
                "tail endpoint drifted from solved bogie at tick $it"
            )
            fixture.train.commit()
        }
    }

    @Test
    fun `moving the same long train succeeds when reverse active path is selected`() {
        val fixture = fixtureWithTailAcrossJunction()
        fixture.junction.viewBA.selectNextRail(fixture.previous.viewAB)

        AsyncTickTestSupport.run { fixture.train.moveTrainForward(0.5) }

        assertFalse(fixture.train.isDerailed, fixture.train.derailReason)
    }

    @Test
    fun `legacy restore fails when the saved tail needs an unset reverse active path`() {
        val fixture = fixtureWithTailAcrossJunction()
        val saved = fixture.train.save().copy(headLocation = null, tailLocation = null)
        fixture.world.removeTrain(fixture.train)
        fixture.junction.viewBA.activeRail = null
        check(fixture.junction.viewBA.nextRails.size == 2)
        check(fixture.train.tailBogie.location.forward(-fixture.train.tailCar.tailBogieToEndPointOffset) == null) {
            "tail=${fixture.train.tailBogie.location.rail.id}/${fixture.train.tailBogie.location.facing}@" +
                "${fixture.train.tailBogie.location.arcLength} offset=" +
                fixture.train.tailCar.tailBogieToEndPointOffset +
                " active=${fixture.junction.viewBA.activeRail?.rail?.id} result=" +
                fixture.train.tailBogie.location.forward(-fixture.train.tailCar.tailBogieToEndPointOffset)
        }

        assertThrows(CannotCreateTrainException::class.java) {
            saved.load(fixture.world)
        }
    }

    @Test
    fun `exact saved train ends restore across the same unset reverse active path`() {
        val fixture = fixtureWithTailAcrossJunction()
        val saved = fixture.train.save()
        fixture.world.removeTrain(fixture.train)
        fixture.junction.viewBA.activeRail = null

        assertDoesNotThrow {
            saved.load(fixture.world)
        }
    }

    private fun fixtureWithTailAcrossJunction(): Fixture = fixture()

    private fun fixture(): Fixture {
        val world = createTestWorld()
        // Both branches expose their A endpoints at the same physical anchor, matching the
        // persisted s100-2 topology (4c9... A connected to b15... A and an alternate branch).
        val previous = rail(world, Vector3d(30.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0))
        val alternate = rail(world, Vector3d(30.0, 0.0, 0.0), Vector3d(30.0, 0.0, 20.0))
        val junction = rail(world, Vector3d(30.0, 0.0, 0.0), Vector3d(51.0, 0.0, 0.0))
        val next = rail(world, Vector3d(51.0, 0.0, 0.0), Vector3d(61.0, 0.0, 0.0))
        val long = rail(world, Vector3d(61.0, 0.0, 0.0), Vector3d(261.0, 0.0, 0.0))
        val staging = rail(world, Vector3d(-300.0, 0.0, 0.0), Vector3d(-100.0, 0.0, 0.0))
        CoreRail.connect(previous, junction)
        CoreRail.connect(alternate, junction)
        CoreRail.connect(junction, next)
        CoreRail.connect(next, long)
        previous.viewBA.selectNextRail(junction.viewAB)
        junction.viewBA.selectNextRail(previous.viewAB)
        junction.viewAB.selectNextRail(next.viewAB)
        next.viewAB.selectNextRail(long.viewAB)
        val stagingLocation = RailLocationWithFacing(staging.viewAB, 100.0)
        val cars = List(5) { index ->
            CoreCar(
                id = "car-$index",
                bogies = listOf(CoreBogie.create(stagingLocation), CoreBogie.create(stagingLocation)),
                bogiesOffsets = listOf(6.25, -6.25),
                length = 17.2,
                floorHeight = 1.0,
                connectorLengthAtA = 0.4,
                connectorLengthAtB = 0.4,
                world = world,
                savedExtensionData = emptyMap()
            )
        }
        val train = CoreTrain("asymmetric-turnout", cars, world, emptyMap())
        train.setLocation(RailLocationWithFacing(long.viewAB, 56.0))
        train.commitMaintenancePlacement()
        check(train.tailBogie.location.rail.id == junction.id) {
            "tail bogie=${train.tailBogie.location.rail.id}@${train.tailBogie.location.arcLength}"
        }
        return Fixture(world, previous, junction, train)
    }

    private fun rail(world: CoreWorld, from: Vector3d, to: Vector3d): CoreRail = CoreRail(
        world,
        straightCurve(from, to),
        mutableListOf(),
        mutableListOf()
    )

    private fun straightCurve(from: Vector3d, to: Vector3d): PrimeCurve {
        val tangent = Vector3d(to).sub(from).normalize()
        val normal = Vector3d(0.0, 1.0, 0.0)
        val length = from.distance(to)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(from, tangent, normal, 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(to, tangent, normal, length, Curve.SegmentType.LINE_END)
        ))
    }

    private data class Fixture(
        val world: CoreWorld,
        val previous: CoreRail,
        val junction: CoreRail,
        val train: CoreTrain
    )
}
