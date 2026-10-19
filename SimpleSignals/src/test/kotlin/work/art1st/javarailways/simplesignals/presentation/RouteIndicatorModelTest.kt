package work.art1st.javarailways.simplesignals.presentation

import com.google.gson.JsonParser
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.joml.Quaterniond
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.model.ItemAppearance
import work.art1st.javarailways.bukkit.api.model.ModelPart
import work.art1st.javarailways.bukkit.api.model.ModelSelectionBox
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO

class RouteIndicatorModelTest {
    private val directory = Path.of(requireNotNull(javaClass.getResource("/examples/route-indicator")).toURI())

    @Test
    fun `shipped indicator loads all eleven appearances with resolved client assets`() {
        val model = ModelPackageLoader.load(directory.resolve("models/jr_route_indicator"))
            .models.getValue("jr_route_indicator:head")
        val states = listOf("closed") + (0..9).map(Int::toString)
        assertEquals(states.toSet(), model.variants.keys)
        assertEquals(mapOf("display" to "closed"), model.defaultScenes)
        assertEquals(1, model.definition.selectionBoxes().size)
        val group = model.definition.parts().single() as ModelPart.Group
        val housing = group.children()[0] as ModelPart.Item
        val digits = group.children()[1] as ModelPart.Item
        assertNull(housing.appearance().brightness())
        val animation = digits.animations().single()
        assertEquals(states.toSet(), animation.frames().map { it.scene() }.toSet())
        states.forEach { state ->
            assertEquals(mapOf("display" to state), model.variant(state))
            val appearance = animation.frames().single { it.scene() == state }.appearance()
            assertEquals("jr:route_indicator_$state", appearance.itemModelKey())
            assertEquals(ItemAppearance.Brightness(15, 15), appearance.brightness())
        }
        val resourcePack = directory.resolve("resourcepack/assets/jr")
        val meshes = (states + "housing").associateWith { state ->
            val item = JsonParser.parseString(Files.readString(resourcePack.resolve("items/route_indicator_$state.json")))
                .asJsonObject.getAsJsonObject("model")
            assertEquals("minecraft:model", item.get("type").asString)
            val mesh = JsonParser.parseString(Files.readString(resourcePack.resolve(
                "models/${item.get("model").asString.substringAfter(':')}.json"))).asJsonObject
            val texture = mesh.getAsJsonObject("textures").get("surface").asString.substringAfter(':')
            assertNotNull(ImageIO.read(resourcePack.resolve("textures/$texture.png").toFile()))
            mesh.getAsJsonArray("elements").forEach { element ->
                listOf("from", "to").forEach { key ->
                    assertTrue(element.asJsonObject.getAsJsonArray(key).all { it.asDouble in -16.0..32.0 })
                }
            }
            mesh.getAsJsonArray("elements")
        }
        assertTrue(meshes.getValue("closed").isEmpty)
        val shapes = (0..9).map { meshes.getValue(it.toString()).toString() }
        assertEquals(10, shapes.toSet().size)
        assertTrue((0..9).all { meshes.getValue(it.toString()).size() > 0 })
        // Viewed from north, screen-left is positive source X. Keep asymmetric digits readable.
        val leftPixel = meshes.getValue("2").single {
            it.asJsonObject.get("name").asString == "pixel_1_0"
        }.asJsonObject
        assertEquals(10.8, leftPixel.getAsJsonArray("from")[0].asDouble, 1e-9)
        assertEquals(setOf("north"), leftPixel.getAsJsonObject("faces").keySet())
    }

    @Test
    fun `indicator faces approaching trains and preserves preset sides and selection dimensions`() {
        val model = ModelPackageLoader.load(directory.resolve("models/jr_route_indicator"))
            .models.getValue("jr_route_indicator:head")
        assertEquals(listOf(ModelSelectionBox(-0.40625, 0.0, -0.3125, 0.40625, 1.0625, 0.125)),
            model.definition.selectionBoxes())
        val group = model.definition.parts().single() as ModelPart.Group
        val pose = group.pose()
        val partRotation = Quaterniond(pose.rotationX(), pose.rotationY(), pose.rotationZ(), pose.rotationW())
        val curve = PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(10.0, 20.0, 30.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(Vector3d(20.0, 20.0, 30.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 10.0, Curve.SegmentType.LINE_END)
        ))
        for (facing in ViewDirection.entries) {
            val direction = if (facing == ViewDirection.AB) 1.0 else -1.0
            for ((presetName, side) in listOf("left" to -2.0, "right" to 2.0)) {
                val placement = ModelPlacement.RailAttached(RailPositionSnapshot(UUID.randomUUID(), facing, 5.0))
                    .applyPreset(model.railPlacementPresets.getValue(presetName))
                val root = ModelPlacementCalculator.calculate(placement, curve)
                val rootRotation = Quaterniond(root.rotationX(), root.rotationY(), root.rotationZ(), root.rotationW())
                // Include the client's ItemDisplay Y180 transformation.
                val front = rootRotation.mul(partRotation).rotateY(Math.PI).transform(Vector3d(0.0, 0.0, -1.0))
                assertEquals(-direction, front.x, 1e-10)
                assertEquals(0.0, front.y, 1e-10)
                assertEquals(0.0, front.z, 1e-10)
                assertEquals(15.0, root.x(), 1e-10)
                assertEquals(20.0, root.y(), 1e-10)
                assertEquals(30.0 + direction * side, root.z(), 1e-10)
            }
        }
    }

    companion object {
        @JvmStatic @BeforeAll
        fun bootstrapPaperItems() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
