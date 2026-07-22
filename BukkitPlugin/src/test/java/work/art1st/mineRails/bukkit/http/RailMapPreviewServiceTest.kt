package work.art1st.mineRails.bukkit.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import work.art1st.mineRails.basic.BasicPlugin
import work.art1st.mineRails.basic.BasicWorldData
import work.art1st.mineRails.core.api.util.plugin.ExtensionData
import work.art1st.mineRails.core.physics.train.createStraightRailFromSegments
import work.art1st.mineRails.core.physics.train.createTestWorld
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator
import java.io.File

class RailMapPreviewServiceTest {
    @TempDir lateinit var directory:File

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
