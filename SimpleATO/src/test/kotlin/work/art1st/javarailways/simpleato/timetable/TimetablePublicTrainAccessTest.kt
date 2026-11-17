package work.art1st.javarailways.simpleato.timetable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.api.route.WayPoint
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.physics.train.onOwnerForTest
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.api.depot.DepotId
import work.art1st.javarailways.simpleato.api.depot.DepotTrainSelector
import work.art1st.javarailways.simpleato.api.destination.DestinationRole
import work.art1st.javarailways.simpleato.api.destination.OperationalDestination
import work.art1st.javarailways.simpleato.api.destination.OperationalDestinationService
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.simpleato.restriction.emptyInspectionRuntime
import work.art1st.javarailways.simpleato.restriction.emptyTrainRestrictionSnapshots
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.testing.KinematicTrainActuator
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry

class TimetablePublicTrainAccessTest {
    @ParameterizedTest
    @ValueSource(strings = ["id", "waypoint", "depot"])
    fun `service admission and candidate selection use the public train contract`(acquisition: String) {
        TestWorldFixture.create().use { fixture ->
            val physicalWorld = fixture.world
            val data = BasicWorldData()
            TestSimpleATOWorldDataRegistry.register(physicalWorld, data)
            try {
                val rail = createStraightRailFromSegments(physicalWorld,
                    PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1, step = 1.0))
                val physicalTrain = createTrainWithConfig("T", rail, physicalWorld, listOf(10.0), carIdPrefix = "T/car-")
                val publicTrain = object : Train by physicalTrain {}
                val world = PublicTrainWorld(physicalWorld, physicalTrain, publicTrain)
                val origin = if (acquisition == "depot") "depot@one" else "origin"
                if (acquisition == "depot") {
                    OperationalDestinationService(physicalWorld).register(OperationalDestination(origin,
                        rail.id, physicalTrain.publishedFrame.head.arcLengthInAbMeters, ViewDirection.AB,
                        DestinationRole.DepotStablingPointRole("depot", "one")))
                } else {
                    data.wayPoints.add(WayPoint(origin, rail.id, physicalTrain.publishedFrame.head.arcLengthInAbMeters))
                }
                data.wayPoints.add(WayPoint("target", rail.id, physicalTrain.publishedFrame.head.arcLengthInAbMeters + 30.0))
                val first = when (acquisition) {
                    "id" -> TimetableStep.Check(origin)
                    "waypoint" -> TimetableStep.At(0L, TimetableAction.TakeFromWaypoint(listOf(origin)))
                    else -> TimetableStep.At(0L, TimetableAction.TakeFromDepot(DepotTrainSelector(DepotId("depot"))))
                }
                world.simpleAtoWorldData.timetables.add(Timetable("public", services = listOf(
                    TimetableService("service", if (acquisition == "id") TrainSelector.TrainId(publicTrain.id)
                        else TrainSelector.AnyTrain, listOf(first, TimetableStep.At(0L, TimetableAction.GoTo("target")))))))

                // The facade exposes public operations while Core retains the real controller,
                // train identity, physics state and registration. This test does not substitute physics.
                val inspection = emptyInspectionRuntime()
                val profile = AtoSafetyProfile()
                val ato = SimpleATO(physicalTrain, physicalWorld, KinematicTrainActuator(10.0, profile),
                    profile, inspection::maximumDeclaredLeadTimeSeconds)
                if (acquisition == "id") {
                    physicalWorld.onOwnerForTest { physicalTrain.setController(ato) }
                }
                val dispatcher = TimetableDispatcher(
                    authorityStoppingMarginMeters = profile.safetyDistanceMeters,
                    routeAdmissionContextProvider = GrantingRouteAdmissionTestContext { physicalWorld },
                    signalRestrictionSnapshots = emptyTrainRestrictionSnapshots,
                    inspectionRuntime = inspection,
                    infrastructureDependencyService = acceptingInfrastructureDependencyService(),
                    atoFactory = TimetableAtoFactory { selected, host ->
                        assertSame(publicTrain, selected)
                        assertSame(world, host)
                        // This host unwraps its forwarding view before registering the controller
                        // on the real Core train; the public facade delegates that binding operation.
                        ato
                    }
                )
                val started = physicalWorld.onOwnerForTest { dispatcher.startService(world, "public", "service") }
                assertInstanceOf(TimetableServiceStartResult.Started::class.java, started, started.toString())
                val runId = TimetableServiceRunId("public", "service")
                assertEquals(publicTrain.id, dispatcher.status(runId)?.trainId)
                assertSame(ato, publicTrain.controller)
                assertFalse(world.simpleAtoWorldData.atoCoordinator.isStalled)
                physicalWorld.onOwnerForTest { dispatcher.unitDisplaysForTrain(world, publicTrain.id, "public", 0L) }
            } finally {
                TestSimpleATOWorldDataRegistry.unregister(physicalWorld)
            }
        }
    }

    @Test
    fun `admission explicitly rejects a train without the frame required by ATO construction`() {
        TestWorldFixture.create().use { fixture ->
            val physicalWorld = fixture.world
            val data = BasicWorldData()
            TestSimpleATOWorldDataRegistry.register(physicalWorld, data)
            try {
                val rail = createStraightRailFromSegments(physicalWorld,
                    PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1, step = 1.0))
                val physicalTrain = createTrainWithConfig("T", rail, physicalWorld, listOf(10.0), carIdPrefix = "T/car-")
                val publicTrain = object : Train by physicalTrain {
                    override val publishedFrame get() = null
                }
                val world = PublicTrainWorld(physicalWorld, physicalTrain, publicTrain)
                data.wayPoints.add(WayPoint("origin", rail.id, physicalTrain.publishedFrame.head.arcLengthInAbMeters))
                data.wayPoints.add(WayPoint("target", rail.id, physicalTrain.publishedFrame.head.arcLengthInAbMeters + 30.0))
                world.simpleAtoWorldData.timetables.add(Timetable("no-frame", services = listOf(
                    TimetableService("service", TrainSelector.TrainId(publicTrain.id), listOf(
                        TimetableStep.Check("origin"), TimetableStep.At(0L, TimetableAction.GoTo("target")))))))
                val inspection = emptyInspectionRuntime()
                val profile = AtoSafetyProfile()
                val dispatcher = TimetableDispatcher(
                    authorityStoppingMarginMeters = profile.safetyDistanceMeters,
                    routeAdmissionContextProvider = GrantingRouteAdmissionTestContext { physicalWorld },
                    signalRestrictionSnapshots = emptyTrainRestrictionSnapshots,
                    inspectionRuntime = inspection,
                    infrastructureDependencyService = acceptingInfrastructureDependencyService(),
                    atoFactory = TimetableAtoFactory { train, host ->
                        SimpleATO(train, host, KinematicTrainActuator(10.0, profile), profile,
                            inspection::maximumDeclaredLeadTimeSeconds)
                    }
                )
                val result = physicalWorld.onOwnerForTest { dispatcher.startService(world, "no-frame", "service") }
                val failure = assertInstanceOf(TimetableServiceStartResult.Failure::class.java, result)
                assertTrue(failure.reason.contains("ATO requires a published frame"), failure.toString())
                assertEquals(null, physicalTrain.controller)
                assertTrue(dispatcher.statuses().isEmpty())
            } finally {
                TestSimpleATOWorldDataRegistry.unregister(physicalWorld)
            }
        }
    }

    private class PublicTrainWorld(
        private val delegate: CoreWorld,
        private val physicalTrain: Train,
        private val publicTrain: Train
    ) : World by delegate {
        override val allTrains: Collection<Train>
            get() = delegate.allTrains.map { if (it === physicalTrain) publicTrain else it }

        override fun getTrain(name: String): Train? =
            delegate.getTrain(name)?.let { if (it === physicalTrain) publicTrain else it }
    }
}
