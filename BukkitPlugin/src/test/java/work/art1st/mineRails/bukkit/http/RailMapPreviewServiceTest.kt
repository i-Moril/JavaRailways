package work.art1st.mineRails.bukkit.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import work.art1st.mineRails.basic.BasicPlugin
import work.art1st.mineRails.basic.BasicWorldData
import work.art1st.mineRails.core.api.util.plugin.ExtensionData
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.physics.train.CoreBogie
import work.art1st.mineRails.core.physics.train.CoreCar
import work.art1st.mineRails.core.physics.train.CoreTrain
import work.art1st.mineRails.core.physics.train.createStraightRailFromSegments
import work.art1st.mineRails.core.physics.train.createTestWorld
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator
import java.io.File

class RailMapPreviewServiceTest {
    @TempDir lateinit var directory:File

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
        CoreTrain.create("train-"+"t".repeat(10_000),listOf(car),world,name="Airport Express")
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
