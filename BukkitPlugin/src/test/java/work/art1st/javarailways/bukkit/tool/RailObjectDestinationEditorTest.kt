package work.art1st.javarailways.bukkit.tool

import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicPlugin
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyRequest
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
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
        val anchor = WayPoint("standalone", rail.id, 20.0)
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
        val anchor = WayPoint("standalone", rail.id, 20.0)
        world.basicWorldData.wayPoints.add(anchor)
        val playerId = UUID.randomUUID()
        editor.seedSession(playerId, anchor, destination = null, pending = false)
        val session = editor.configUI.getPlayerSession(playerId)
        session.setValue(editor.keys.role, DestinationRoleType.STATION_BERTH)
        session.setValue(editor.keys.stationId, " central ")
        session.setValue(editor.keys.berthId, " one ")
        session.setValue(editor.keys.doorSide, DoorSide.NONE)
        session.setValue(editor.keys.stoppingAreaId, "platform")
        session.setValue(editor.keys.minimumTrainLength, 20.0)
        session.setValue(editor.keys.maximumTrainLength, 40.0)
        session.setValue(editor.keys.approachDirection, WayPointApproachOption.B_TO_A)
        val updated = editor.readEdit(playerId, anchor, original = null)

        assertEquals("central@one", updated.id)
        assertEquals(ViewDirection.BA, updated.approachDirection)

        editor.save(world, anchor, updated, pending = false)

        assertNull(world.basicWorldData.wayPoints[anchor.id])
        assertEquals(updated, world.simpleAtoWorldData.destinations[updated.id])
        assertEquals(WayPoint("central@one", rail.id, 20.0), world.basicWorldData.wayPoints[updated.id])
        val station = world.simpleAtoWorldData.stations[StationId("central")]!!
        val berth = world.simpleAtoWorldData.stations.berth(station.berthRefs.single())!!
        assertEquals("one", berth.berthRef.berthId)
        assertEquals(DoorSide.NONE, berth.doorSide)
        assertEquals("platform", berth.stoppingAreaId)
        assertEquals(20.0, berth.minimumTrainLengthMeters)
        assertEquals(40.0, berth.maximumTrainLengthMeters)
        editor.seedSession(playerId, updated.toWayPoint(), updated, pending = false)
        assertEquals(updated, editor.readEdit(playerId, updated.toWayPoint(), updated))
        session.setValue(editor.keys.minimumTrainLength, 50.0)
        assertThrows(IllegalArgumentException::class.java) {
            editor.readEdit(playerId, updated.toWayPoint(), updated)
        }
        assertEquals(updated, world.simpleAtoWorldData.destinations[updated.id])
        assertEquals(listOf(berth), world.basicWorldData.railObjects.objectsOnRail(rail.id))

        editor.remove(world, updated.toWayPoint())

        assertNull(world.simpleAtoWorldData.destinations[updated.id])
        assertNull(world.basicWorldData.wayPoints[updated.id])
        assertNull(world.simpleAtoWorldData.stations[StationId("central")])
        assertEquals(emptyList<Any>(), world.basicWorldData.railObjects.objectsOnRail(rail.id))
    }

    @Test
    fun `destination callback rejects changed world session target and obsolete dialog identity`() {
        attachWorldData()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
        val anchor = WayPoint("same-id", rail.id, 20.0)
        world.basicWorldData.wayPoints.add(anchor)
        val destination = stationDestination(anchor)
        val playerId = UUID.randomUUID()
        val session = editor.configUI.getPlayerSession(playerId)
        val worldId = UUID.randomUUID()
        fun bukkitWorld() = java.lang.reflect.Proxy.newProxyInstance(org.bukkit.World::class.java.classLoader,
            arrayOf(org.bukkit.World::class.java)) { _, method, _ ->
            check(method.name == "getUID")
            worldId
        } as org.bukkit.World
        val platformWorld = bukkitWorld()
        val edit = RailObjectWand.DestinationEdit(platformWorld, world, anchor, destination, false, session)
        assertTrue(edit.isCurrent(edit, platformWorld, world, session, anchor, destination))
        assertFalse(edit.isCurrent(edit, bukkitWorld(), world, session, anchor, destination),
            "a reloaded world with the same UUID is still a different lifetime")
        val otherCoreWorld = createTestWorld()
        try {
            assertFalse(edit.isCurrent(edit, platformWorld, otherCoreWorld, session, anchor, destination))
        } finally {
            otherCoreWorld.physicsCoordinator.close()
            (otherCoreWorld.tickManager as AutoCloseable).close()
        }
        val newerDialog = RailObjectWand.DestinationEdit(platformWorld, world, anchor, destination, false, session)
        assertFalse(edit.isCurrent(newerDialog, platformWorld, world, session, anchor, destination))
        assertFalse(edit.isCurrent(null, platformWorld, world, session, anchor, destination))
        editor.configUI.removePlayerSession(playerId)
        val newSession = editor.configUI.getPlayerSession(playerId)
        assertFalse(edit.isCurrent(edit, platformWorld, world, newSession, anchor, destination))
        assertFalse(edit.isCurrent(edit, platformWorld, world, session, anchor.copy(), destination),
            "same-ID replacement must not inherit a stale form")
        assertFalse(edit.isCurrent(edit, platformWorld, world, session, null, destination))
        assertFalse(edit.isCurrent(edit, platformWorld, world, session, anchor, destination.copy()))
        assertFalse(edit.isCurrent(edit, platformWorld, world, session, anchor, null))
        assertSame(anchor, world.basicWorldData.wayPoints[anchor.id])
    }

    private fun stationDestination(anchor: WayPoint) = OperationalDestination(
        "central@one", anchor.railId, anchor.arcLength,
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
