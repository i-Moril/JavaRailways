package work.art1st.mineRails.basic.timetable.js

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import work.art1st.mineRails.basic.WayPoint
import work.art1st.mineRails.basic.api.depot.DepotId
import work.art1st.mineRails.basic.api.destination.DestinationRole
import work.art1st.mineRails.basic.api.destination.OperationalDestination
import work.art1st.mineRails.basic.api.destination.OperationalDestinationService
import work.art1st.mineRails.basic.api.station.DoorSide
import work.art1st.mineRails.basic.api.station.Station
import work.art1st.mineRails.basic.api.station.StationBerth
import work.art1st.mineRails.basic.api.station.StationBerthRef
import work.art1st.mineRails.basic.api.station.StationId
import work.art1st.mineRails.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.mineRails.basic.pathFinding.basicWorldData
import work.art1st.mineRails.basic.timetable.TimetableAction
import work.art1st.mineRails.basic.timetable.TimetableStep
import work.art1st.mineRails.basic.timetable.TrainSelector
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.physics.rail.CoreRail
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.mineRails.core.physics.train.createStraightRailFromSegments
import work.art1st.mineRails.core.physics.train.createTestWorld
import work.art1st.mineRails.core.physics.train.createTrainWithConfig
import java.io.File

class TimetableScriptLoaderTest {
    private val worlds = mutableListOf<work.art1st.mineRails.core.physics.CoreWorld>()

    @TempDir
    lateinit var tempDir: File

    @AfterEach
    fun unregisterWorlds() {
        worlds.forEach(TestBasicWorldDataRegistry::unregister)
        worlds.clear()
    }

    @Test
    fun `loads js timetable dsl`() {
        val world = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
        world.basicWorldData.wayPoints.add(WayPoint("depot1", "depot1", rail.id, 0.0))
        world.basicWorldData.wayPoints.add(WayPoint("a0", "a0", rail.id, 20.0))
        world.basicWorldData.wayPoints.add(WayPoint("b0", "b0", rail.id, 80.0))
        val script = File(tempDir, "demo.js")
        script.writeText(
            """
            function main(timetable) {
              timetable.setPeriodDays(1)
              timetable.service("001", ["depot1"], function(t) {
                t.at(1000).goto("a0")
                t.at(2000).stop(100)
                t.at(2200).goto("b0")
              })
            }
            """.trimIndent()
        )

        val result = TimetableScriptLoader(world).load(script)

        assertTrue(result is TimetableScriptLoadResult.Success, result.toString())
        val success = result as TimetableScriptLoadResult.Success
        assertEquals("demo", success.timetable.id)
        assertEquals(1L, success.timetable.periodDays)
        assertEquals(24_000L, success.timetable.periodTicks)
        assertEquals(1, success.timetable.services.size)
        assertEquals(TrainSelector.WayPointPool(listOf("depot1")), success.timetable.services.single().trainSelector)
        assertEquals(3, success.timetable.services.single().steps.size)
        assertEquals(TimetableAction.GoTo("a0"), (success.timetable.services.single().steps[0] as TimetableStep.At).action)
        assertEquals(2, success.validation.routes.size)
    }

    @Test
    fun `loads strict depot selector options`() {
        val world = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(world)
        val rail = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(100.0, 0.0, 0.0))
        val returnRail = rail(world, Vector3d(100.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0))
        CoreRail.connect(rail, returnRail)
        CoreRail.connect(returnRail, rail)
        OperationalDestinationService(world).register(
            OperationalDestination(
                "north-one",
                "North one",
                rail.id,
                10.0,
                work.art1st.mineRails.core.api.physics.rail.ViewDirection.AB,
                DestinationRole.DepotStablingPointRole("north", "one")
            )
        )
        world.basicWorldData.wayPoints.add(WayPoint("B", "B", returnRail.id, 50.0))
        val script = File(tempDir, "depot.js")
        script.writeText(
            """
            function main(timetable) {
              timetable.setPeriodDays(1)
              timetable.service("001", function(t) {
                t.at(0).takeFromDepot("north", {
                  tags: ["metro"],
                  minimumCars: 4,
                  maximumCars: 8,
                  minimumSpeedMetersPerSecond: 16.5,
                  capabilities: ["doors"]
                })
                t.at(100).goto("B")
                t.at(200).storeAtDepot("north", { stablingPoints: ["one"] })
              })
            }
            """.trimIndent()
        )

        val result = TimetableScriptLoader(world).load(script)

        assertTrue(result is TimetableScriptLoadResult.Success, result.toString())
        val action = (((result as TimetableScriptLoadResult.Success)
            .timetable.services.single().steps.first()) as TimetableStep.At).action as
            TimetableAction.TakeFromDepot
        assertEquals(DepotId("north"), action.selector.depotId)
        assertEquals(setOf("metro"), action.selector.requiredTags)
        assertEquals(4, action.selector.minimumCars)
        assertEquals(8, action.selector.maximumCars)
        assertEquals(16.5, action.selector.minimumSpeedMetersPerSecond)
        assertEquals(setOf("doors"), action.selector.requiredCapabilities)
        assertEquals(
            TimetableAction.StoreAtDepot(DepotId("north"), setOf("one")),
            ((result.timetable.services.single().steps.last()) as TimetableStep.At).action
        )
    }

    @Test
    fun `rejects unknown depot selector options`() {
        val world = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(world)
        val script = File(tempDir, "bad-depot.js")
        script.writeText(
            """
            function main(timetable) {
              timetable.setPeriodDays(1)
              timetable.service("bad", function(t) {
                t.at(0).takeFromDepot("north", { surprise: true })
              })
            }
            """.trimIndent()
        )

        val result = TimetableScriptLoader(world).load(script)

        assertTrue(result is TimetableScriptLoadResult.Failure)
        assertTrue((result as TimetableScriptLoadResult.Failure).message.contains("未知 options"))
    }

    @Test
    fun `loads strict explicit station call options`() {
        val world = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(120.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val originArc = train.head.location.arcLength
        world.basicWorldData.wayPoints.add(WayPoint("A", "A", rail.id, originArc))
        world.basicWorldData.wayPoints.add(WayPoint("C", "C", rail.id, originArc + 80.0))
        val stationId = StationId("central")
        val berthRef = StationBerthRef(stationId, "one")
        val berth = StationBerth(berthRef, train.head.location.forward(30.0)!!)
        world.basicWorldData.stations.register(
            Station(stationId, "Central", listOf(berthRef)),
            listOf(berth)
        )
        val script = File(tempDir, "station.js")
        script.writeText(
            """
            function main(timetable) {
              timetable.setPeriodDays(1)
              timetable.service("station-service", "T1", function(t) {
                t.check("A")
                t.at(100).call("central", "one", {
                  minimumDwellTicks: 40,
                  plannedDepartureTick: 200,
                  stoppingOffsetMetersBefore: 1.5,
                  doorSide: "left"
                })
                t.at(200).goto("C")
              })
            }
            """.trimIndent()
        )

        val result = TimetableScriptLoader(world).load(script)

        assertTrue(result is TimetableScriptLoadResult.Success, result.toString())
        val action = ((result as TimetableScriptLoadResult.Success)
            .timetable.services.single().steps[1] as TimetableStep.At).action
        assertEquals(
            TimetableAction.CallStation(
                stationId = "central",
                berthId = "one",
                minimumDwellTicks = 40L,
                plannedDepartureTick = 200L,
                stoppingOffsetMetersBefore = 1.5,
                doorSideOverride = DoorSide.LEFT
            ),
            action
        )
    }

    @Test
    fun `rejects unknown station call options`() {
        val world = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(world)
        val script = File(tempDir, "bad-station.js")
        script.writeText(
            """
            function main(timetable) {
              timetable.setPeriodDays(1)
              timetable.service("bad", "T1", function(t) {
                t.at(0).call("central", "one", { surprise: true })
              })
            }
            """.trimIndent()
        )

        val result = TimetableScriptLoader(world).load(script)

        assertTrue(result is TimetableScriptLoadResult.Failure)
        assertTrue((result as TimetableScriptLoadResult.Failure).message.contains("未知 options"))
    }

    @Test
    fun `rejects scripts without a declared period`() {
        val world = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(world)
        val script = File(tempDir, "missing-period.js")
        script.writeText(
            """
            function main(timetable) {
              timetable.service("empty", "T1", function(t) {})
            }
            """.trimIndent()
        )

        val result = TimetableScriptLoader(world).load(script)

        assertTrue(result is TimetableScriptLoadResult.Failure)
        assertTrue((result as TimetableScriptLoadResult.Failure).message.contains("setPeriodDays"))
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

    @Test
    fun `reports js syntax errors`() {
        val world = createTestWorld().also(worlds::add)
        TestBasicWorldDataRegistry.getOrCreate(world)
        val script = File(tempDir, "broken.js")
        script.writeText("function main(timetable) {")

        val result = TimetableScriptLoader(world).load(script)

        assertTrue(result is TimetableScriptLoadResult.Failure)
        assertTrue((result as TimetableScriptLoadResult.Failure).message.contains("JS 语法错误"), result.message)
    }
}
