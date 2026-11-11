package work.art1st.javarailways.bukkit.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.physics.train.onOwnerForTest

class TrainPlacementSelectionTest {
    @Test
    fun `explicit distinct train names produce distinct car identities`() {
        val first = "S100-01"
        val second = "S100-02"
        val ids = listOf(first, second).flatMap { train -> (0 until 5).map { carIdForTrain(train, it) } }
        assertEquals(10, ids.toSet().size)
        assertEquals("S100-01-car-0", ids.first())
        assertEquals("S100-02-car-4", ids.last())
    }

    @Test
    fun `train label shows identity only when the display name differs`() {
        TestWorldFixture.create().use { fixture ->
            val rail = createStraightRailFromSegments(fixture.world, PrimeCurveGenerator.buildStraight(40.0, 1))
            val train = createTrainWithConfig("S100-01", rail, fixture.world, listOf(8.0))
            assertEquals("S100-01", trainIdentityLabel(train))
            fixture.world.onOwnerForTest { fixture.world.renameTrain(train, "Morning Express") }
            assertEquals("Morning Express [S100-01]", trainIdentityLabel(train))
        }
    }

    @Test
    fun `head remains at selected point when both directions fit`() {
        TestWorldFixture.create().use { fixture ->
            val rail = createStraightRailFromSegments(fixture.world, PrimeCurveGenerator.buildStraight(40.0, 1))
            val selected = RailLocationWithFacing(rail.viewAB, 20.0)

            val result = requireNotNull(findPlaceableHeadLocation(selected, 15.0))
            assertSame(selected, result)
            val train = createTrainWithConfig("placed", rail, fixture.world, listOf(15.0))
            fixture.world.onOwnerForTest {
                train.setLocation(result)
                train.commit()
            }
            assertEquals(20.0, train.head.location.arcLengthIn(ViewDirection.AB), 1e-6)
            assertEquals(5.0, train.tail.location.arcLengthIn(ViewDirection.AB), 1e-6)
        }
    }

    @Test
    fun `opposite direction may fit without translating selected head`() {
        TestWorldFixture.create().use { fixture ->
            val rail = createStraightRailFromSegments(fixture.world, PrimeCurveGenerator.buildStraight(20.0, 1))
            val selected = RailLocationWithFacing(rail.viewAB, 2.0)

            val result = requireNotNull(findPlaceableHeadLocation(selected, 15.0))
            assertEquals(ViewDirection.BA, result.facing)
            assertEquals(2.0, result.arcLengthIn(ViewDirection.AB), 1e-8)
            assertEquals(0.0, result.position.distance(selected.position), 1e-8)
        }
    }

    @Test
    fun `insufficient room at selected point is rejected even when relocation would fit`() {
        TestWorldFixture.create().use { fixture ->
            val rail = createStraightRailFromSegments(fixture.world, PrimeCurveGenerator.buildStraight(20.0, 1))
            val selected = RailLocationWithFacing(rail.viewAB, 10.0)

            // A 15m train fits elsewhere on this rail, but neither side of the chosen point fits.
            assertNotNull(RailLocationWithFacing(rail.viewAB, 15.0).forward(-15.0))
            assertNull(findPlaceableHeadLocation(selected, 15.0))
            assertEquals(10.0, selected.arcLength, 1e-8)
            assertEquals(0, fixture.world.allTrains.size)
        }
    }

    @Test
    fun `exact available length is accepted but full consist exceeding it is rejected`() {
        TestWorldFixture.create().use { fixture ->
            val rail = createStraightRailFromSegments(fixture.world, PrimeCurveGenerator.buildStraight(20.0, 1))
            val selected = RailLocationWithFacing(rail.viewAB, 10.0)

            assertSame(selected, findPlaceableHeadLocation(selected, 10.0))
            assertNull(findPlaceableHeadLocation(selected, 10.5))
        }
    }
}
