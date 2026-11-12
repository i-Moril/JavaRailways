package work.art1st.javarailways.simpleato.api.destination

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.Assertions.assertAll
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

import work.art1st.javarailways.core.physics.createRailForTest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.simpleato.api.depot.DepotId
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.simpleato.api.station.StationId
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.simpleato.routing.RouteBuildResult
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.routing.OperationalRoutePlanner

class OperationalDestinationTest {
    private val worldFixtures = mutableListOf<TestWorldFixture>()

    @AfterEach
    fun closeOwnedWorldFixtures() {
        val fixtures = worldFixtures.toList()
        worldFixtures.clear()
        assertAll(fixtures.flatMap { fixture ->
            listOf(
                Executable { TestSimpleATOWorldDataRegistry.unregister(fixture.world) },
                Executable { fixture.close() }
            )
        })
    }



    @Test
    fun `last berth replacement and failed replacement preserve station metadata`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1))
        val service = OperationalDestinationService(world)
        val id = StationId("central")
        val original = OperationalDestination(
            id = "central@one", railId = rail.id, arcLengthInAbMeters = 20.0,
            role = DestinationRole.StationBerthRole(id, "one", DoorSide.LEFT)
        )
        service.register(original)
        val metadata = JsonObject(mapOf("notice" to JsonPrimitive("Transfer here")))
        world.simpleAtoWorldData.stations.updateMetadata(id, metadata, JsonObject(emptyMap()))
        val moved = original.copy(arcLengthInAbMeters = 40.0)
        service.replace(original.id, moved)
        assertEquals(metadata, world.simpleAtoWorldData.stations[id]!!.metadata)
        assertThrows(IllegalArgumentException::class.java) {
            service.replace(moved.id, moved.copy(railId = java.util.UUID.randomUUID()))
        }
        assertEquals(moved, service[moved.id])
        assertEquals(metadata, world.simpleAtoWorldData.stations[id]!!.metadata)
        service.remove(moved.id)
        service.register(original)
        assertEquals(JsonObject(emptyMap()), world.simpleAtoWorldData.stations[id]!!.metadata)
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
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val service = OperationalDestinationService(world)
        service.register(destination("A", rail.id, 20.0, ViewDirection.AB))
        service.register(destination("B", rail.id, 80.0, ViewDirection.AB))
        service.register(destination("wrong-way", rail.id, 80.0, ViewDirection.BA))

        val result = OperationalRoutePlanner(world).buildRoute(
            "A",
            "B",
            requiredGoalDirection = ViewDirection.AB,
            requiredStartDirection = ViewDirection.AB
        ) as RouteBuildResult.Success

        assertEquals(ViewDirection.AB, result.route.segments.single().railViewRef.direction)
        assertEquals(20.0, world.basicWorldData.wayPoints["A"]?.arcLength)
        assertEquals(DestinationRoleType.REGULAR, world.simpleAtoWorldData.destinations["A"]?.role?.type)
        assertTrue(OperationalRoutePlanner(world).buildRoute(
            "A",
            "wrong-way",
            requiredGoalDirection = ViewDirection.BA,
            requiredStartDirection = ViewDirection.AB
        ) is RouteBuildResult.Failure)
    }

    @Test
    fun `destinations default to no approach restriction`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val destination = OperationalDestination(
            id = "unrestricted",
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
                railId = rail.id,
                arcLengthInAbMeters = 60.0,
                role = stationRole
            )
        )

        assertEquals(destination, service["unrestricted"])
        assertNull(service["missing"])
        assertNull(world.simpleAtoWorldData.destinations["unrestricted"]?.approachDirection)
        val berth = requireNotNull(world.simpleAtoWorldData.stations.berth(stationRole.berthRef))
        assertNull(berth.approachDirection)
        assertEquals(60.0, berth.location.arcLengthIn(ViewDirection.AB), 1.0e-9)
    }

    @Test
    fun `station destination may register a berth without door operations`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
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
                railId = rail.id,
                arcLengthInAbMeters = 50.0,
                approachDirection = ViewDirection.AB,
                role = role
            )
        )

        val berth = requireNotNull(world.simpleAtoWorldData.stations.berth(role.berthRef))
        assertEquals(DoorSide.NONE, berth.doorSide)
    }

    @Test
    fun `station destinations atomically aggregate directed passive berths`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(120.0, segmentCount = 1)
        )
        val service = OperationalDestinationService(world)
        val stationId = StationId("central")
        val firstRole = DestinationRole.StationBerthRole(
            stationId = stationId,
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
                rail.id,
                40.0,
                ViewDirection.AB,
                firstRole
            )
        )
        service.register(
            OperationalDestination(
                "central@one-down",
                rail.id,
                80.0,
                ViewDirection.BA,
                secondRole
            )
        )

        val station = world.simpleAtoWorldData.stations[stationId]!!
        val down = world.simpleAtoWorldData.stations.berth(secondRole.berthRef)!!
        assertEquals(listOf(firstRole.berthRef, secondRole.berthRef), station.berthRefs)
        assertEquals(ViewDirection.BA, down.approachDirection)
        assertEquals(80.0, down.location.arcLengthIn(ViewDirection.AB), 1.0e-9)
        assertEquals(DoorSide.RIGHT, down.doorSide)
        assertSame(down, world.basicWorldData.railObjects.get(down.ref))

        service.remove("central@one-up")

        assertEquals(listOf(secondRole.berthRef), world.simpleAtoWorldData.stations[stationId]?.berthRefs)
        assertNull(world.simpleAtoWorldData.stations.berth(firstRole.berthRef))
    }

    @Test
    fun `depot destination registers one passive stabling point`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
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
                rail.id,
                30.0,
                ViewDirection.AB,
                role
            )
        )

        val point = world.simpleAtoWorldData.depots.stablingPoints(DepotId("north")).single()
        assertEquals("north@one", point.point.destinationId)
        assertEquals(90.0, point.point.usableLengthMeters)
        assertEquals(setOf("metro"), point.point.allowedTags)
    }

    @Test
    fun `rail deletion removes destinations and derived station and waypoint records`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
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
                rail.id,
                40.0,
                ViewDirection.AB,
                role
            )
        )
        service.register(destination("regular", rail.id, 60.0, ViewDirection.BA))
        world.basicWorldData.wayPoints.add(WayPoint("legacy", rail.id, 70.0))

        assertEquals(
            setOf("station@platform", "regular"),
            service.removeAllOnRail(rail.id).mapTo(linkedSetOf()) { it.id }
        )

        assertNull(world.simpleAtoWorldData.destinations["station@platform"])
        assertNull(world.basicWorldData.wayPoints["regular"])
        assertNull(world.basicWorldData.wayPoints["legacy"])
        assertNull(world.simpleAtoWorldData.stations.berth(role.berthRef))
    }

    @Test
    fun `rail replacement migrates destination position direction and derived records`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val oldRail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val replacement = world.createRailForTest(oldRail.curve.reversed)
        val service = OperationalDestinationService(world)
        val role = DestinationRole.StationBerthRole(
            StationId("station"),
            berthId = "platform",
            doorSide = DoorSide.RIGHT
        )
        service.register(
            OperationalDestination(
                "station@platform",
                oldRail.id,
                25.0,
                ViewDirection.AB,
                role
            )
        )
        world.basicWorldData.wayPoints.add(WayPoint("legacy", oldRail.id, 20.0))

        val migrated = service.migrateAllOnRail(oldRail.id, replacement.id)

        assertEquals(listOf("station@platform"), migrated.map { it.id })
        val destination = world.simpleAtoWorldData.destinations["station@platform"]!!
        assertEquals(replacement.id, destination.railId)
        assertEquals(75.0, destination.arcLengthInAbMeters, 0.01)
        assertEquals(ViewDirection.BA, destination.approachDirection)
        val berth = world.simpleAtoWorldData.stations.berth(role.berthRef)!!
        assertEquals(replacement.id, berth.location.rail.id)
        assertEquals(ViewDirection.BA, berth.approachDirection)
        assertEquals(replacement.id, world.basicWorldData.wayPoints["legacy"]?.railId)
        assertEquals(80.0, requireNotNull(world.basicWorldData.wayPoints["legacy"]).arcLength, 0.01)
    }

    @Test
    fun `split and merge migration preserve destination arc coordinates`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
        val original = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val service = OperationalDestinationService(world)
        service.register(destination("before", original.id, 25.0, ViewDirection.AB))
        service.register(destination("after", original.id, 75.0, ViewDirection.BA))
        world.basicWorldData.wayPoints.add(WayPoint("legacy", original.id, 75.0))
        val splitCurves = original.curve.splitAt(50.0)
        IsolatedSimulationThreadMarker.run { world.removeRail(original) }
        val beforeRail = world.createRailForTest(splitCurves.first)
        val afterRail = world.createRailForTest(splitCurves.second)

        service.migrateAfterRailSplit(original.id, beforeRail.id, afterRail.id, 50.0)

        assertEquals(beforeRail.id, world.simpleAtoWorldData.destinations["before"]?.railId)
        assertEquals(25.0, world.simpleAtoWorldData.destinations["before"]?.arcLengthInAbMeters)
        assertEquals(afterRail.id, world.simpleAtoWorldData.destinations["after"]?.railId)
        assertEquals(25.0, world.simpleAtoWorldData.destinations["after"]?.arcLengthInAbMeters)
        assertEquals(afterRail.id, world.basicWorldData.wayPoints["legacy"]?.railId)
        assertEquals(25.0, world.basicWorldData.wayPoints["legacy"]?.arcLength)
        val mergedCurve = beforeRail.curve.concatenate(afterRail.curve)
        IsolatedSimulationThreadMarker.run { world.removeRail(beforeRail) }
        IsolatedSimulationThreadMarker.run { world.removeRail(afterRail) }
        val merged = world.createRailForTest(mergedCurve)

        service.migrateAfterRailMerge(beforeRail.id, afterRail.id, merged.id, 50.0)

        assertEquals(25.0, world.simpleAtoWorldData.destinations["before"]?.arcLengthInAbMeters)
        assertEquals(75.0, world.simpleAtoWorldData.destinations["after"]?.arcLengthInAbMeters)
        assertEquals(75.0, world.basicWorldData.wayPoints["legacy"]?.arcLength)
        assertEquals(merged.id, world.basicWorldData.wayPoints["legacy"]?.railId)
        assertEquals(setOf(merged.id), world.simpleAtoWorldData.destinations.all().map { it.railId }.toSet())
    }

    @Test
    fun `destination replacement rolls role and legacy waypoint together`() {
        val world = TestWorldFixture.create().also(worldFixtures::add).world
        TestSimpleATOWorldDataRegistry.getOrCreate(world)
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
                rail.id,
                30.0,
                ViewDirection.AB,
                stationRole
            )
        )

        assertNull(world.simpleAtoWorldData.destinations["old"])
        assertNull(world.basicWorldData.wayPoints["old"])
        assertEquals("station@platform", world.basicWorldData.wayPoints["station@platform"]?.id)
        assertTrue(world.simpleAtoWorldData.stations.berth(stationRole.berthRef) != null)
    }

    private fun destination(
        id: String,
        railId: java.util.UUID,
        arc: Double,
        direction: ViewDirection
    ) = OperationalDestination(id, railId, arc, direction, DestinationRole.Regular)
}
