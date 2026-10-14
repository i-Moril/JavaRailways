package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.core.physics.train.onOwnerForTest

import work.art1st.javarailways.simpleato.simpleAtoWorldData

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.testing.KinematicTrainActuator
import work.art1st.javarailways.simpleato.api.destination.DestinationRole
import work.art1st.javarailways.simpleato.api.destination.OperationalDestination
import work.art1st.javarailways.simpleato.api.destination.OperationalDestinationService
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.simpleato.api.station.StationId
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.simpleato.restriction.emptyInspectionRuntime
import work.art1st.javarailways.simpleato.restriction.emptyTrainRestrictionSnapshots
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.simpleato.timetable.TimetableAction
import work.art1st.javarailways.simpleato.timetable.TimetableDispatcher
import work.art1st.javarailways.simpleato.timetable.TimetableServiceRunId
import work.art1st.javarailways.simpleato.timetable.TimetableServiceStartResult
import work.art1st.javarailways.simpleato.timetable.TimetableStep
import work.art1st.javarailways.simpleato.timetable.TimetableWaypointProgress
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.util.AsyncTickTestSupport

class ManualNavigationTest {
    @Test
    fun `parser accepts pass points and strict stop JSON`() {
        val points = ManualNavigationParser.parse(listOf(
            "+wp1",
            "sta1@1{\"minimumDwellTicks\":40,\"doorSide\":\"left\"}"
        ))

        assertEquals(2, points.size)
        assertTrue(points[0].pass)
        assertEquals("wp1", points[0].id)
        assertFalse(points[1].pass)
        assertEquals(40L, points[1].options?.minimumDwellTicks)
        assertEquals(DoorSide.LEFT, points[1].options?.doorSide)
    }

    @Test
    fun `parser requires a stopping endpoint and rejects options on pass points`() {
        assertThrows(IllegalArgumentException::class.java) {
            ManualNavigationParser.parse(listOf("+wp1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManualNavigationParser.parse(listOf("+wp1{\"minimumDwellTicks\":10}", "stop"))
        }
    }

    @Test
    fun `factory navigation runs from current origin through via to stopping endpoint`() {
        val profile = AtoSafetyProfile(
            accelerationMetersPerSecondSquared = 1.0,
            serviceDecelerationMetersPerSecondSquared = 1.0,
            reactionTimeSeconds = 0.0,
            safetyDistanceMeters = 2.0,
            stopToleranceMeters = 0.25
        )
        var admissionWorld: CoreWorld? = null
        val inspectionRuntime = emptyInspectionRuntime()
        val dispatcher = TimetableDispatcher(
            routeAdmissionContextProvider = GrantingRouteAdmissionTestContext { admissionWorld },
            signalRestrictionSnapshots = emptyTrainRestrictionSnapshots,
            inspectionRuntime = inspectionRuntime,
            infrastructureDependencyService = acceptingInfrastructureDependencyService(),
            atoFactory = TimetableAtoFactory { train, world ->
                admissionWorld = world as CoreWorld
                SimpleATO(
                    train,
                    world,
                    KinematicTrainActuator(8.0, profile),
                    profile,
                    inspectionRuntime::maximumDeclaredLeadTimeSeconds
                )
            }
        )
        val world = createTestWorld()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        try {
            val rail = createStraightRailFromSegments(
                world,
                PrimeCurveGenerator.buildStraight(140.0, segmentCount = 1)
            )
            val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
            val originArc = train.head.location.arcLength
            data.wayPoints.add(WayPoint("via", "via", rail.id, originArc + 25.0))
            data.wayPoints.add(WayPoint("stop", "stop", rail.id, originArc + 60.0))
            OperationalDestinationService(world).register(
                OperationalDestination(
                    id = "current",
                    displayName = "current",
                    railId = rail.id,
                    arcLengthInAbMeters = originArc,
                    approachDirection = ViewDirection.AB,
                    role = DestinationRole.Regular
                )
            )
            val timetable = ManualNavigationTimetableFactory.create(
                world,
                timetableId = "navigate",
                serviceId = "driver",
                trainId = train.id,
                originWayPointId = "current",
                points = listOf(
                    ManualNavigationWaypoint("via", pass = true),
                    ManualNavigationWaypoint("stop", pass = false)
                )
            )
            world.simpleAtoWorldData.timetables.add(timetable)
            val runId = TimetableServiceRunId("navigate", "driver")
            val observed = ArrayList<TimetableWaypointProgress>()
            val subscription = dispatcher.observeWaypointProgress(runId) { observed += it }
            try {
                assertTrue(world.onOwnerForTest { dispatcher.startService(world, "navigate", "driver") } is
                    TimetableServiceStartResult.Started)
                for (tick in 0 until 2_000) {
                    val ato = train.controller as SimpleATO
                    AsyncTickTestSupport.run { ato.tick(50.0) }
                    ato.commit()
                    world.onOwnerForTest { dispatcher.tick(world) }
                    if (observed.size == 2) break
                }
            } finally {
                subscription.close()
            }

            assertEquals(listOf("via", "stop"), observed.map { it.waypointId })
            assertEquals(listOf(true, false), observed.map { it.pass })
            assertTrue(observed[1].timetableElapsedTicks > observed[0].timetableElapsedTicks)
        } finally {
            TestSimpleATOWorldDataRegistry.unregister(world)
        }
    }

    @Test
    fun `factory groups pass points into missions ending at each stop`() {
        val world = createTestWorld()
        TestSimpleATOWorldDataRegistry.register(world, BasicWorldData())
        try {
            val rail = createStraightRailFromSegments(
                world,
                PrimeCurveGenerator.buildStraight(120.0, segmentCount = 1)
            )
            world.basicWorldData.wayPoints.add(WayPoint("origin", "origin", rail.id, 0.0))
            world.basicWorldData.wayPoints.add(WayPoint("via", "via", rail.id, 20.0))
            world.basicWorldData.wayPoints.add(WayPoint("plain", "plain", rail.id, 40.0))
            OperationalDestinationService(world).register(
                OperationalDestination(
                    id = "central@one",
                    displayName = "Central",
                    railId = rail.id,
                    arcLengthInAbMeters = 80.0,
                    role = DestinationRole.StationBerthRole(
                        stationId = StationId("central"),
                        berthId = "one",
                        doorSide = DoorSide.RIGHT
                    )
                )
            )
            val timetable = ManualNavigationTimetableFactory.create(
                world = world,
                timetableId = "navigate",
                serviceId = "driver",
                trainId = "T1",
                originWayPointId = "origin",
                points = listOf(
                    ManualNavigationWaypoint("via", pass = true),
                    ManualNavigationWaypoint("plain", pass = false),
                    ManualNavigationWaypoint(
                        "central@one",
                        pass = false,
                        options = ManualNavigationOptions(doorSide = DoorSide.LEFT)
                    )
                )
            )

            val steps = timetable.services.single().steps
            assertEquals(TimetableStep.Check("origin"), steps[0])
            assertEquals(
                TimetableAction.GoVia(listOf("via", "plain")),
                (steps[1] as TimetableStep.At).action
            )
            assertTrue((steps[2] as TimetableStep.At).action is TimetableAction.CallStation)
            assertEquals(
                TimetableAction.GoTo("central@one"),
                (steps[3] as TimetableStep.At).action
            )
        } finally {
            TestSimpleATOWorldDataRegistry.unregister(world)
        }
    }
}
