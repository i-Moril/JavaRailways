package work.art1st.javarailways.bukkit.tool

import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicPlugin
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureDependencyRequest
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.api.util.plugin.PluginRegistry
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.util.TickManagerImpl
import work.art1st.javarailways.simpleato.SimpleATOPlugin
import work.art1st.javarailways.simpleato.SimpleATOWorldData
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.api.destination.DestinationRole
import work.art1st.javarailways.simpleato.api.destination.DestinationRoleType
import work.art1st.javarailways.simpleato.api.destination.OperationalDestination
import work.art1st.javarailways.simpleato.api.station.StationId
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotStore
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.timetable.InfrastructureDependencyService
import work.art1st.javarailways.simpleato.timetable.TimetableDispatcher
import work.art1st.javarailways.simpleato.timetable.TimetableRouteAdmissionContextProvider

/** Exercises the real destination transaction, not route/timetable execution. */
class RailObjectDestinationEditorTest {
    init {
        val basicPlugin = BasicPlugin.instanceOrNull() ?: BasicPlugin(0).also { BasicPlugin.INSTANCE = it }
        val atoPlugin = SimpleATOPlugin.instanceOrNull() ?: SimpleATOPlugin(0).also { SimpleATOPlugin.INSTANCE = it }
        PluginRegistry.register(basicPlugin)
        PluginRegistry.register(atoPlugin)
    }

    private val world = createTestWorld()
    private val editor = RailObjectDestinationEditor()

    @AfterEach
    fun closeWorld() {
        world.physicsCoordinator.close()
        (world.tickManager as TickManagerImpl).close()
    }

    @Test
    fun `failed standalone conversion restores the original anchor without role projections`() {
        attachWorldData()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
        val anchor = WayPoint("standalone", "Original", rail.id, 20.0)
        world.basicWorldData.wayPoints.add(anchor)
        val invalid = stationDestination(anchor).copy(arcLengthInAbMeters = 101.0)

        assertThrows(IllegalArgumentException::class.java) {
            editor.save(world, anchor, invalid, pending = false)
        }

        assertSame(anchor, world.basicWorldData.wayPoints[anchor.id])
        assertNull(world.basicWorldData.wayPoints[invalid.id])
        assertNull(world.simpleAtoWorldData.destinations[invalid.id])
        assertNull(world.simpleAtoWorldData.stations[StationId("central")])
        assertEquals(emptyList<Any>(), world.basicWorldData.railObjects.objectsOnRail(rail.id))
    }

    @Test
    fun `standalone conversion publishes canonical projections and removal clears them`() {
        attachWorldData()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
        val anchor = WayPoint("standalone", "Original", rail.id, 20.0)
        world.basicWorldData.wayPoints.add(anchor)
        val playerId = UUID.randomUUID()
        editor.seedSession(playerId, anchor, destination = null, pending = false)
        val session = editor.configUI.getPlayerSession(playerId)
        session.setValue(editor.keys.role, DestinationRoleType.STATION_BERTH)
        session.setValue(editor.keys.stationId, " central ")
        session.setValue(editor.keys.berthId, " one ")
        session.setValue(editor.keys.doorSide, DoorSide.NONE)
        session.setValue(editor.keys.approachDirection, WayPointApproachOption.B_TO_A)
        val updated = editor.readEdit(playerId, anchor, original = null)

        assertEquals("central@one", updated.id)
        assertEquals(ViewDirection.BA, updated.approachDirection)

        editor.save(world, anchor, updated, pending = false)

        assertNull(world.basicWorldData.wayPoints[anchor.id])
        assertEquals(updated, world.simpleAtoWorldData.destinations[updated.id])
        assertEquals(WayPoint("central@one", "Original", rail.id, 20.0), world.basicWorldData.wayPoints[updated.id])
        val station = world.simpleAtoWorldData.stations[StationId("central")]!!
        val berth = world.simpleAtoWorldData.stations.berth(station.berthRefs.single())!!
        assertEquals("one", berth.berthRef.berthId)
        assertEquals(DoorSide.NONE, berth.doorSide)
        assertEquals(listOf(berth), world.basicWorldData.railObjects.objectsOnRail(rail.id))

        editor.remove(world, updated.toWayPoint())

        assertNull(world.simpleAtoWorldData.destinations[updated.id])
        assertNull(world.basicWorldData.wayPoints[updated.id])
        assertNull(world.simpleAtoWorldData.stations[StationId("central")])
        assertEquals(emptyList<Any>(), world.basicWorldData.railObjects.objectsOnRail(rail.id))
    }

    private fun stationDestination(anchor: WayPoint) = OperationalDestination(
        "central@one", anchor.displayName, anchor.railId, anchor.arcLength,
        role = DestinationRole.StationBerthRole(StationId("central"), "Central", "one", DoorSide.NONE)
    )

    private fun attachWorldData() {
        val basic = BasicWorldData()
        world.registerExtensionData(BasicPlugin.INSTANCE, basic)
        val coordinator = AtoWorldCoordinator(safetyInterlock = basic.safetyInterlock)
        val restrictions = TrainRestrictionSnapshotStore(coordinator.worldId)
        val inspection = InspectionRuntime(restrictions) { _, _, failure -> throw failure }
        val epochs = SignalEpochRuntime(coordinator.worldId, restrictions, restrictions::completeEpoch, inspection::markDirty)
        val dispatcher = TimetableDispatcher(
            TimetableRouteAdmissionContextProvider { error("Editor must not request routes") },
            restrictions,
            inspection,
            object : InfrastructureDependencyService {
                override fun set(request: InfrastructureDependencyRequest): Nothing =
                    error("Editor must not set timetable dependencies")
                override fun remove(owner: InfrastructureOwner): Nothing =
                    error("Editor must not remove timetable dependencies")
            }
        )
        world.registerExtensionData(SimpleATOPlugin.INSTANCE, SimpleATOWorldData(
            coordinator, dispatcher, epochs, restrictions, inspection, basic.railObjects, basic.safetyInterlock
        ))
    }
}
