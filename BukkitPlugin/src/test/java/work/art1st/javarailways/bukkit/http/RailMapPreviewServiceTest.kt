package work.art1st.javarailways.bukkit.http

import work.art1st.javarailways.core.physics.train.onOwnerForTest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import work.art1st.javarailways.basic.BasicPlugin
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.core.api.util.plugin.ExtensionData
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.train.CoreBogie
import work.art1st.javarailways.core.physics.train.CoreCar
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import java.io.File

class RailMapPreviewServiceTest {
    @TempDir lateinit var directory:File

    @OptIn(ExtensionData::class)
    @Test
    fun `moving a marker to another rail invalidates the cached map`() {
        work.art1st.javarailways.core.physics.train.TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            val plugin = BasicPlugin.instanceOrNull() ?: BasicPlugin(0).also { BasicPlugin.INSTANCE = it }
            val data = BasicWorldData()
            world.registerExtensionData(plugin, data)
            val first = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
            val second = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, 1))
            val marker = work.art1st.javarailways.basic.api.route.WayPoint("stop", first.id, 20.0)
            data.wayPoints.add(marker)
            RailMapPreviewService(directory).use { service ->
                val before = service.render(service.capture("world", world))
                data.wayPoints.remove(marker.id)
                data.wayPoints.add(marker.copy(railId = second.id))
                val after = service.render(service.capture("world", world))
                org.junit.jupiter.api.Assertions.assertNotEquals(before, after)
                val moved = Json.parseToJsonElement(after).jsonObject["markers"]!!.jsonArray.single().jsonObject
                assertEquals("stop", moved["id"]!!.jsonPrimitive.content)
                assertEquals(second.id.toString(), moved["railId"]!!.jsonPrimitive.content)
                assertEquals(after, File(directory, "world.json").readText())
            }
        }
    }

    @OptIn(ExtensionData::class)
    @Test
    fun `streams train names and immutable carriage geometry`() {
        val world=createTestWorld()
        val plugin=BasicPlugin.instanceOrNull()?:BasicPlugin(0).also{BasicPlugin.INSTANCE=it}
        world.registerExtensionData(plugin,BasicWorldData())
        val rail=createStraightRailFromSegments(world,PrimeCurveGenerator.buildStraight(100.0,1))
        val first=RailLocationWithFacing(rail.viewAB,10.0)
        val bogieA=CoreBogie.create(first)
        val bogieB=CoreBogie.create(first.forward(12.0)!!)
        val car=CoreCar.create("car-"+"c".repeat(10_000),bogieA,bogieB,length=20.0,world=world)
        world.onOwnerForTest {
            CoreTrain.create("train-"+"t".repeat(10_000),listOf(car),world,name="Airport Express")
        }
        RailMapPreviewService(directory).use { service ->
            service.capture("world",world)
            val train=Json.parseToJsonElement(service.trains("world")).jsonArray.single().jsonObject
            assertEquals(256,train["id"]!!.jsonPrimitive.content.length)
            assertEquals("Airport Express",train["name"]!!.jsonPrimitive.content)
            val streamedCar=train["cars"]!!.jsonArray.single().jsonObject
            assertEquals(256,streamedCar["id"]!!.jsonPrimitive.content.length)
            assertEquals(20.0,streamedCar["lengthMeters"]!!.toString().toDouble())
            assertTrue(streamedCar.keys.containsAll(setOf("x","y","z","directionX","directionZ")))
        }
    }

    @OptIn(ExtensionData::class)
    @Test
    fun `samples a bounded map file on the dedicated worker`() {
        val world=createTestWorld()
        val plugin=BasicPlugin.instanceOrNull()?:BasicPlugin(0).also{BasicPlugin.INSTANCE=it}
        world.registerExtensionData(plugin,BasicWorldData())
        // Regression: multiplying the final sample index used to round one ULP past this length.
        createStraightRailFromSegments(world,PrimeCurveGenerator.buildStraight(53.18068377346187,1))
        RailMapPreviewService(directory).use { service ->
            val source=service.capture("world",world)
            val content=service.render(source)
            val result=Json.parseToJsonElement(content).jsonObject
            val points=result["rails"]!!.jsonArray.single().jsonObject["points"]!!.jsonArray
            assertTrue(points.size in 2..512)
            assertTrue(File(directory,"world.json").isFile)
            assertEquals(content,service.render(source))
            service.invalidate("world")
            assertTrue(!File(directory,"world.json").exists())
            assertThrows(HttpFailure::class.java){service.trains("world")}
            assertEquals(409,assertThrows(HttpFailure::class.java){service.render(source)}.status)
            assertTrue(!File(directory,"world.json").exists())
        }
    }
}
