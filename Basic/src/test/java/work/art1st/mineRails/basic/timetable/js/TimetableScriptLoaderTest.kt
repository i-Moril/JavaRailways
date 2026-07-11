package work.art1st.mineRails.basic.timetable.js

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import work.art1st.mineRails.basic.WayPoint
import work.art1st.mineRails.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.mineRails.basic.pathFinding.basicWorldData
import work.art1st.mineRails.basic.timetable.TimetableAction
import work.art1st.mineRails.basic.timetable.TimetableStep
import work.art1st.mineRails.basic.timetable.TrainSelector
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.mineRails.core.physics.train.createStraightRailFromSegments
import work.art1st.mineRails.core.physics.train.createTestWorld
import java.io.File

class TimetableScriptLoaderTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `loads js timetable dsl`() {
        val world = createTestWorld()
        TestBasicWorldDataRegistry.getOrCreate(world)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
        world.basicWorldData.wayPoints.add(WayPoint("depot1", "depot1", rail.id, 0.0))
        world.basicWorldData.wayPoints.add(WayPoint("a0", "a0", rail.id, 20.0))
        world.basicWorldData.wayPoints.add(WayPoint("b0", "b0", rail.id, 80.0))
        val script = File(tempDir, "demo.js")
        script.writeText(
            """
            function main(timetable) {
              timetable.setPeriod(16000)
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
        assertEquals(16000, success.timetable.periodTicks)
        assertEquals(1, success.timetable.services.size)
        assertEquals(TrainSelector.WayPointPool(listOf("depot1")), success.timetable.services.single().trainSelector)
        assertEquals(3, success.timetable.services.single().steps.size)
        assertEquals(TimetableAction.GoTo("a0"), (success.timetable.services.single().steps[0] as TimetableStep.At).action)
        assertEquals(2, success.validation.routes.size)
    }

    @Test
    fun `reports js syntax errors`() {
        val world = createTestWorld()
        TestBasicWorldDataRegistry.getOrCreate(world)
        val script = File(tempDir, "broken.js")
        script.writeText("function main(timetable) {")

        val result = TimetableScriptLoader(world).load(script)

        assertTrue(result is TimetableScriptLoadResult.Failure)
        assertTrue((result as TimetableScriptLoadResult.Failure).message.contains("JS 语法错误"), result.message)
    }
}
