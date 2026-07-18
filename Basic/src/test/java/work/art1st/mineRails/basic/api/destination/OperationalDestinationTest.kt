package work.art1st.mineRails.basic.api.destination

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import work.art1st.mineRails.basic.api.depot.DepotId
import work.art1st.mineRails.basic.api.station.DoorSide
import work.art1st.mineRails.basic.api.station.StationId
import work.art1st.mineRails.basic.pathFinding.RailDestination
import work.art1st.mineRails.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.mineRails.basic.pathFinding.basicWorldData
import work.art1st.mineRails.basic.route.RouteBuildResult
import work.art1st.mineRails.basic.route.RoutePlanner
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.mineRails.core.physics.train.createStraightRailFromSegments
import work.art1st.mineRails.core.physics.train.createTestWorld

class OperationalDestinationTest {
    private val worlds = mutableListOf<work.art1st.mineRails.core.physics.CoreWorld>()

    @AfterEach
    fun unregisterWorlds() {
        worlds.forEach(TestBasicWorldDataRegistry::unregister)
        worlds.clear()
    }

    @Test
    fun `regular destinations adapt to directed mission waypoints`() {
        val world = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val service = OperationalDestinationService(world)
        service.register(destination("A", rail.id, 20.0, ViewDirection.AB))
        service.register(destination("B", rail.id, 80.0, ViewDirection.AB))
        service.register(destination("wrong-way", rail.id, 80.0, ViewDirection.BA))

        val result = RoutePlanner(world).buildRoute("A", "B") as RouteBuildResult.Success

        assertEquals(ViewDirection.AB, result.route.segments.single().railViewRef.direction)
        assertEquals(20.0, world.basicWorldData.wayPoints["A"]?.arcLength)
        assertEquals(DestinationRoleType.REGULAR, world.basicWorldData.destinations["A"]?.role?.type)
        assertTrue(RoutePlanner(world).buildRoute("A", "wrong-way") is RouteBuildResult.Failure)

        val migrated = service.registerLegacy(RailDestination(rail.viewAB, "legacy"))
        assertEquals(ViewDirection.AB, migrated.approachDirection)
        assertEquals(0.0, migrated.arcLengthInAbMeters)
        assertEquals("legacy", world.basicWorldData.wayPoints["legacy"]?.id)
    }

    @Test
    fun `destinations default to no approach restriction`() {
        val world = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val destination = OperationalDestination(
            id = "unrestricted",
            displayName = "Unrestricted",
            railId = rail.id,
            arcLengthInAbMeters = 50.0
        )

        val service = OperationalDestinationService(world)
        service.register(destination)
        val stationRole = DestinationRole.StationBerthRole(
            stationId = StationId("unrestricted-station"),
            berthId = "platform",
            doorSide = DoorSide.LEFT
        )
        service.register(
            OperationalDestination(
                id = "unrestricted-platform",
                displayName = "Unrestricted platform",
                railId = rail.id,
                arcLengthInAbMeters = 60.0,
                role = stationRole
            )
        )

        assertNull(world.basicWorldData.destinations["unrestricted"]?.approachDirection)
        val berth = requireNotNull(world.basicWorldData.stations.berth(stationRole.berthRef))
        assertNull(berth.approachDirection)
        assertEquals(60.0, berth.location.arcLengthIn(ViewDirection.AB), 1.0e-9)
    }

    @Test
    fun `station destinations atomically aggregate directed passive berths`() {
        val world = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(120.0, segmentCount = 1)
        )
        val service = OperationalDestinationService(world)
        val stationId = StationId("central")
        val firstRole = DestinationRole.StationBerthRole(
            stationId = stationId,
            stationDisplayName = "Central",
            berthId = "one-up",
            doorSide = DoorSide.LEFT
        )
        val secondRole = DestinationRole.StationBerthRole(
            stationId = stationId,
            berthId = "one-down",
            doorSide = DoorSide.RIGHT
        )
        service.register(
            OperationalDestination(
                "central-up",
                "Central up",
                rail.id,
                40.0,
                ViewDirection.AB,
                firstRole
            )
        )
        service.register(
            OperationalDestination(
                "central-down",
                "Central down",
                rail.id,
                80.0,
                ViewDirection.BA,
                secondRole
            )
        )

        val station = world.basicWorldData.stations[stationId]!!
        val down = world.basicWorldData.stations.berth(secondRole.berthRef)!!
        assertEquals(listOf(firstRole.berthRef, secondRole.berthRef), station.berthRefs)
        assertEquals(ViewDirection.BA, down.approachDirection)
        assertEquals(80.0, down.location.arcLengthIn(ViewDirection.AB), 1.0e-9)
        assertEquals(DoorSide.RIGHT, down.doorSide)
        assertSame(down, world.basicWorldData.railObjects.get(down.ref))

        service.remove("central-up")

        assertEquals(listOf(secondRole.berthRef), world.basicWorldData.stations[stationId]?.berthRefs)
        assertNull(world.basicWorldData.stations.berth(firstRole.berthRef))
    }

    @Test
    fun `depot destination registers one passive stabling point`() {
        val world = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val service = OperationalDestinationService(world)
        val role = DestinationRole.DepotStablingPointRole(
            depotId = "north",
            stablingPointId = "one",
            usableLengthMeters = 90.0,
            allowedTags = setOf("metro")
        )

        service.register(
            OperationalDestination(
                "north-one",
                "North one",
                rail.id,
                30.0,
                ViewDirection.AB,
                role
            )
        )

        val point = world.basicWorldData.depots.stablingPoints(DepotId("north")).single()
        assertEquals("north-one", point.point.destinationId)
        assertEquals(90.0, point.point.usableLengthMeters)
        assertEquals(setOf("metro"), point.point.allowedTags)
    }

    @Test
    fun `destination replacement rolls role and legacy waypoint together`() {
        val world = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val service = OperationalDestinationService(world)
        service.register(destination("old", rail.id, 30.0, ViewDirection.AB))
        val stationRole = DestinationRole.StationBerthRole(
            StationId("station"),
            berthId = "platform",
            doorSide = DoorSide.BOTH
        )

        service.replace(
            "old",
            OperationalDestination(
                "new",
                "New station",
                rail.id,
                30.0,
                ViewDirection.AB,
                stationRole
            )
        )

        assertNull(world.basicWorldData.destinations["old"])
        assertNull(world.basicWorldData.wayPoints["old"])
        assertEquals("new", world.basicWorldData.wayPoints["new"]?.id)
        assertTrue(world.basicWorldData.stations.berth(stationRole.berthRef) != null)
    }

    private fun destination(
        id: String,
        railId: java.util.UUID,
        arc: Double,
        direction: ViewDirection
    ) = OperationalDestination(id, id, railId, arc, direction, DestinationRole.Regular)
}
