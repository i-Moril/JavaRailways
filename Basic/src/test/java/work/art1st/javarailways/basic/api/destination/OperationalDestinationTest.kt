package work.art1st.javarailways.basic.api.destination

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.api.depot.DepotId
import work.art1st.javarailways.basic.api.station.DoorSide
import work.art1st.javarailways.basic.api.station.StationId
import work.art1st.javarailways.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.javarailways.basic.pathFinding.basicWorldData
import work.art1st.javarailways.basic.route.RouteBuildResult
import work.art1st.javarailways.basic.route.RoutePlanner
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld

class OperationalDestinationTest {
    private val worlds = mutableListOf<work.art1st.javarailways.core.physics.CoreWorld>()

    @AfterEach
    fun unregisterWorlds() {
        worlds.forEach(TestBasicWorldDataRegistry::unregister)
        worlds.clear()
    }

    @Test
    fun `role-owned destination IDs use canonical owner at point format`() {
        assertEquals(
            "central@one",
            OperationalDestinationIds.stationBerth(StationId("central"), "one")
        )
        assertEquals(
            "north@track-1",
            OperationalDestinationIds.depotStablingPoint("north", "track-1")
        )
        assertEquals(
            "central/one",
            OperationalDestinationIds.parseStationBerth("central@one").toString()
        )
        assertThrows(IllegalArgumentException::class.java) {
            OperationalDestinationIds.parseStationBerth("central@one@extra")
        }
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
                id = "unrestricted-station@platform",
                displayName = "Unrestricted platform",
                railId = rail.id,
                arcLengthInAbMeters = 60.0,
                role = stationRole
            )
        )

        assertEquals(destination, service["unrestricted"])
        assertNull(service["missing"])
        assertNull(world.basicWorldData.destinations["unrestricted"]?.approachDirection)
        val berth = requireNotNull(world.basicWorldData.stations.berth(stationRole.berthRef))
        assertNull(berth.approachDirection)
        assertEquals(60.0, berth.location.arcLengthIn(ViewDirection.AB), 1.0e-9)
    }

    @Test
    fun `station destination may register a berth without door operations`() {
        val world = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val service = OperationalDestinationService(world)
        val role = DestinationRole.StationBerthRole(
            stationId = StationId("no-door-station"),
            berthId = "platform",
            doorSide = DoorSide.NONE
        )

        service.register(
            OperationalDestination(
                id = "no-door-station@platform",
                displayName = "No-door platform",
                railId = rail.id,
                arcLengthInAbMeters = 50.0,
                approachDirection = ViewDirection.AB,
                role = role
            )
        )

        val berth = requireNotNull(world.basicWorldData.stations.berth(role.berthRef))
        assertEquals(DoorSide.NONE, berth.doorSide)
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
                "central@one-up",
                "Central up",
                rail.id,
                40.0,
                ViewDirection.AB,
                firstRole
            )
        )
        service.register(
            OperationalDestination(
                "central@one-down",
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

        service.remove("central@one-up")

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
                "north@one",
                "North one",
                rail.id,
                30.0,
                ViewDirection.AB,
                role
            )
        )

        val point = world.basicWorldData.depots.stablingPoints(DepotId("north")).single()
        assertEquals("north@one", point.point.destinationId)
        assertEquals(90.0, point.point.usableLengthMeters)
        assertEquals(setOf("metro"), point.point.allowedTags)
    }

    @Test
    fun `rail deletion removes destinations and derived station and waypoint records`() {
        val world = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val service = OperationalDestinationService(world)
        val role = DestinationRole.StationBerthRole(
            StationId("station"),
            berthId = "platform",
            doorSide = DoorSide.LEFT
        )
        service.register(
            OperationalDestination(
                "station@platform",
                "Platform",
                rail.id,
                40.0,
                ViewDirection.AB,
                role
            )
        )
        service.register(destination("regular", rail.id, 60.0, ViewDirection.BA))
        world.basicWorldData.wayPoints.add(WayPoint("legacy", "Legacy", rail.id, 70.0))

        assertEquals(
            setOf("station@platform", "regular"),
            service.removeAllOnRail(rail.id).mapTo(linkedSetOf()) { it.id }
        )

        assertNull(world.basicWorldData.destinations["station@platform"])
        assertNull(world.basicWorldData.wayPoints["regular"])
        assertNull(world.basicWorldData.wayPoints["legacy"])
        assertNull(world.basicWorldData.stations.berth(role.berthRef))
    }

    @Test
    fun `rail replacement migrates destination position direction and derived records`() {
        val world = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(world)
        val oldRail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val replacement = work.art1st.javarailways.core.physics.rail.CoreRail(
            world,
            oldRail.curve.reversed,
            mutableListOf(),
            mutableListOf()
        )
        val service = OperationalDestinationService(world)
        val role = DestinationRole.StationBerthRole(
            StationId("station"),
            berthId = "platform",
            doorSide = DoorSide.RIGHT
        )
        service.register(
            OperationalDestination(
                "station@platform",
                "Platform",
                oldRail.id,
                25.0,
                ViewDirection.AB,
                role
            )
        )
        world.basicWorldData.wayPoints.add(WayPoint("legacy", "Legacy", oldRail.id, 20.0))

        val migrated = service.migrateAllOnRail(oldRail.id, replacement.id)

        assertEquals(listOf("station@platform"), migrated.map { it.id })
        val destination = world.basicWorldData.destinations["station@platform"]!!
        assertEquals(replacement.id, destination.railId)
        assertEquals(75.0, destination.arcLengthInAbMeters, 0.01)
        assertEquals(ViewDirection.BA, destination.approachDirection)
        val berth = world.basicWorldData.stations.berth(role.berthRef)!!
        assertEquals(replacement.id, berth.location.rail.id)
        assertEquals(ViewDirection.BA, berth.approachDirection)
        assertEquals(replacement.id, world.basicWorldData.wayPoints["legacy"]?.railId)
        assertEquals(80.0, requireNotNull(world.basicWorldData.wayPoints["legacy"]).arcLength, 0.01)
    }

    @Test
    fun `split and merge migration preserve destination arc coordinates`() {
        val world = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(world)
        val original = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val service = OperationalDestinationService(world)
        service.register(destination("before", original.id, 25.0, ViewDirection.AB))
        service.register(destination("after", original.id, 75.0, ViewDirection.BA))
        world.basicWorldData.wayPoints.add(WayPoint("legacy", "Legacy", original.id, 75.0))
        val splitCurves = original.curve.splitAt(50.0)
        world.removeRail(original)
        val beforeRail = work.art1st.javarailways.core.physics.rail.CoreRail(
            world, splitCurves.first, mutableListOf(), mutableListOf()
        )
        val afterRail = work.art1st.javarailways.core.physics.rail.CoreRail(
            world, splitCurves.second, mutableListOf(), mutableListOf()
        )

        service.migrateAfterRailSplit(original.id, beforeRail.id, afterRail.id, 50.0)

        assertEquals(beforeRail.id, world.basicWorldData.destinations["before"]?.railId)
        assertEquals(25.0, world.basicWorldData.destinations["before"]?.arcLengthInAbMeters)
        assertEquals(afterRail.id, world.basicWorldData.destinations["after"]?.railId)
        assertEquals(25.0, world.basicWorldData.destinations["after"]?.arcLengthInAbMeters)
        assertEquals(afterRail.id, world.basicWorldData.wayPoints["legacy"]?.railId)
        assertEquals(25.0, world.basicWorldData.wayPoints["legacy"]?.arcLength)
        val mergedCurve = beforeRail.curve.concatenate(afterRail.curve)
        world.removeRail(beforeRail)
        world.removeRail(afterRail)
        val merged = work.art1st.javarailways.core.physics.rail.CoreRail(
            world, mergedCurve, mutableListOf(), mutableListOf()
        )

        service.migrateAfterRailMerge(beforeRail.id, afterRail.id, merged.id, 50.0)

        assertEquals(25.0, world.basicWorldData.destinations["before"]?.arcLengthInAbMeters)
        assertEquals(75.0, world.basicWorldData.destinations["after"]?.arcLengthInAbMeters)
        assertEquals(75.0, world.basicWorldData.wayPoints["legacy"]?.arcLength)
        assertEquals(merged.id, world.basicWorldData.wayPoints["legacy"]?.railId)
        assertEquals(setOf(merged.id), world.basicWorldData.destinations.all().map { it.railId }.toSet())
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
                "station@platform",
                "New station",
                rail.id,
                30.0,
                ViewDirection.AB,
                stationRole
            )
        )

        assertNull(world.basicWorldData.destinations["old"])
        assertNull(world.basicWorldData.wayPoints["old"])
        assertEquals("station@platform", world.basicWorldData.wayPoints["station@platform"]?.id)
        assertTrue(world.basicWorldData.stations.berth(stationRole.berthRef) != null)
    }

    private fun destination(
        id: String,
        railId: java.util.UUID,
        arc: Double,
        direction: ViewDirection
    ) = OperationalDestination(id, id, railId, arc, direction, DestinationRole.Regular)
}
