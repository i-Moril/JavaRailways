package work.art1st.javarailways.simplesignals.presentation

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.server.packs.metadata.pack.PackFormat
import net.minecraft.server.packs.metadata.pack.PackMetadataSection
import org.bukkit.Material
import org.joml.Quaterniond
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.model.ModelPart
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.bukkit.api.model.ModelScale
import work.art1st.javarailways.bukkit.api.model.ItemAppearance
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simplesignals.signalpack.SignalPackLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO

class JrFourLightDemoTest {
    private val directory = Path.of(requireNotNull(javaClass.getResource("/examples/jr-four-light")).toURI())
    private val resourcePack = directory.resolve("resourcepack")
    private val variants = listOf("stop", "caution", "slow", "proceed")

    @Test
    fun `actual example loads one head with complete four appearances default and rail presets`() {
        val loaded = SignalPackLoader.load(directory.resolve("signal-packs")).packages.getValue("jr_four_light")
        assertEquals("jr_four_light", loaded.name)
        assertEquals(setOf("jr_four_light:head"), loaded.models.keys)
        val model = loaded.models.getValue("jr_four_light:head").presentation
        val group = model.definition.parts().single() as ModelPart.Group
        assertTrue(group.animations().isEmpty())
        assertEquals(2, group.children().size)
        val housing = group.children()[0] as ModelPart.Item
        val head = group.children()[1] as ModelPart.Item
        assertEquals("jr:four_light_signal_housing", housing.appearance().itemModelKey())
        assertNull(housing.appearance().brightness())
        assertTrue(housing.animations().isEmpty())
        listOf(housing, head).forEach {
            assertEquals(ModelPose.IDENTITY, it.pose())
            assertEquals(ModelScale.UNIT, it.scale())
        }
        assertEquals(Material.PAPER, head.appearance().material())
        assertEquals("jr:four_light_signal_stop", head.appearance().itemModelKey())
        assertNull(head.appearance().customModelData())
        assertEquals(ItemAppearance.Brightness(15, 15), head.appearance().brightness())
        assertEquals(ModelScale.UNIT, head.scale())
        assertEquals(mapOf("lamp" to "stop"), model.defaultScenes)
        assertEquals(variants.toSet(), model.variants.keys)
        val animation = head.animations().single()
        assertEquals("lamp", animation.name())
        assertEquals(variants.toSet(), animation.frames().map { it.scene() }.toSet())
        variants.forEach { variant ->
            assertEquals(mapOf("lamp" to variant), model.variant(variant))
            val frame = animation.frames().single { it.scene() == variant }
            assertEquals(ModelPose.IDENTITY, frame.pose())
            assertEquals(ModelScale.UNIT, frame.scale())
            assertEquals(Material.PAPER, frame.appearance().material())
            assertEquals("jr:four_light_signal_$variant", frame.appearance().itemModelKey())
            assertNull(frame.appearance().customModelData())
            assertEquals(ItemAppearance.Brightness(15, 15), frame.appearance().brightness())
        }
        assertFalse(model.followRailTilt)
        assertEquals(setOf("left", "right"), model.railPlacementPresets.keys)
        assertEquals(-2.0, model.railPlacementPresets.getValue("left").adjustment.z())
        assertEquals(2.0, model.railPlacementPresets.getValue("right").adjustment.z())
        model.railPlacementPresets.values.forEach {
            assertEquals(false, it.followRailTilt)
            assertEquals(1.0, it.adjustment.rotationW()) // No second facing rotation in the preset.
        }
    }

    @Test
    fun `client references resolve to valid sized geometry and decodable texture with current pack metadata`() {
        val pack = json(resourcePack.resolve("pack.mcmeta")).getAsJsonObject("pack")
        val metadata = PackMetadataSection.CLIENT_TYPE.codec().parse(JsonOps.INSTANCE, pack).getOrThrow()
        assertEquals(PackFormat.of(75, 0), metadata.supportedFormats().minInclusive())
        assertEquals(PackFormat.of(75, 0), metadata.supportedFormats().maxInclusive())
        assertEquals("1.21.11", SharedConstants.getCurrentVersion().name())
        val geometryByPart = (variants + "housing").associateWith { variant ->
            val wrapper = json(resourcePack.resolve("assets/jr/items/four_light_signal_$variant.json"))
                .getAsJsonObject("model")
            assertEquals("minecraft:model", wrapper.get("type").asString)
            val geometry = json(asset(wrapper.get("model").asString, "models", ".json"))
            assertFalse(geometry.has("parent"))
            val textures = geometry.getAsJsonObject("textures")
            textures.entrySet().forEach { (_, value) ->
                val image = ImageIO.read(asset(value.asString, "textures", ".png").toFile())
                assertNotNull(image)
                assertEquals(32, image.width)
                assertEquals(16, image.height)
            }
            val elements = geometry.getAsJsonArray("elements").map { it.asJsonObject }
            val corners = elements.flatMap { listOf(it.getAsJsonArray("from"), it.getAsJsonArray("to")) }
            assertTrue(corners.flatMap { it.toList() }.all { it.asDouble in -16.0..32.0 })
            val lenses = elements.filter { it.get("name").asString.endsWith("pixel lens") }
            lenses.forEach { assertEquals(setOf("north"), it.getAsJsonObject("faces").keySet()) }
            elements.forEach { element ->
                element.getAsJsonObject("faces").entrySet().forEach { (_, face) ->
                    val reference = face.asJsonObject.get("texture").asString
                    assertTrue(reference.startsWith("#"))
                    assertTrue(textures.has(reference.removePrefix("#")))
                }
            }
            // NONE does not apply the existing GUI display transform.
            assertEquals(setOf("gui"), geometry.getAsJsonObject("display").keySet())
            elements
        }
        val housing = geometryByPart.getValue("housing")
        assertEquals(29, housing.size)
        val darkLenses = housing.filter { it.get("name").asString.endsWith("pixel lens") }
            .associateBy { it.get("name").asString }
        assertEquals(4, darkLenses.size)
        darkLenses.values.forEach {
            assertEquals(4.0, it.getAsJsonArray("from")[0].asDouble)
            assertEquals(12.0, it.getAsJsonArray("to")[0].asDouble)
            assertEquals(listOf(0.0, 0.0, 4.0, 8.0), it.getAsJsonObject("faces").getAsJsonObject("north")
                .getAsJsonArray("uv").map { coordinate -> coordinate.asDouble })
            assertEquals(7.0, it.getAsJsonArray("from")[2].asDouble)
            assertEquals(8.0, it.getAsJsonArray("to")[2].asDouble)
        }
        val lampsByVariant = mapOf("stop" to listOf(2), "caution" to listOf(3), "slow" to listOf(1, 4), "proceed" to listOf(4))
        val uvByVariant = mapOf("stop" to listOf(4.0), "caution" to listOf(8.0), "slow" to listOf(8.0, 12.0), "proceed" to listOf(12.0))
        val lowerYByLamp = mapOf(1 to 14.0, 2 to 5.0, 3 to -3.0, 4 to -12.0)
        variants.forEach { variant ->
            val lit = geometryByPart.getValue(variant)
            assertEquals(lampsByVariant.getValue(variant).map { "lamp $it pixel lens" }, lit.map { it.get("name").asString })
            lit.forEachIndexed { index, lamp ->
                val dark = darkLenses.getValue(lamp.get("name").asString)
                for (end in listOf("from", "to")) {
                    assertEquals(dark.getAsJsonArray(end)[0].asDouble, lamp.getAsJsonArray(end)[0].asDouble)
                    assertEquals(dark.getAsJsonArray(end)[1].asDouble, lamp.getAsJsonArray(end)[1].asDouble)
                    // Active north faces are 0.05 model units (3.125mm) forward, never coplanar.
                    assertEquals(0.05, dark.getAsJsonArray(end)[2].asDouble - lamp.getAsJsonArray(end)[2].asDouble, 1e-12)
                }
                assertEquals(lowerYByLamp.getValue(lampsByVariant.getValue(variant)[index]), lamp.getAsJsonArray("from")[1].asDouble)
                val u = uvByVariant.getValue(variant)[index]
                assertEquals(listOf(u, 0.0, u + 4.0, 8.0), lamp.getAsJsonObject("faces").getAsJsonObject("north")
                    .getAsJsonArray("uv").map { it.asDouble })
            }
            val corners = (housing + lit).flatMap { listOf(it.getAsJsonArray("from"), it.getAsJsonArray("to")) }
            assertEquals(-16.0, corners.minOf { it[1].asDouble })
            assertEquals(26.0, corners.maxOf { it[1].asDouble })
            assertEquals(42.0, corners.maxOf { it[1].asDouble } - corners.minOf { it[1].asDouble })
            assertEquals(20.0, corners.maxOf { it[0].asDouble } - corners.minOf { it[0].asDouble })
            assertEquals(7.0, corners.maxOf { it[2].asDouble } - corners.minOf { it[2].asDouble })
        }
        assertFalse(Files.exists(resourcePack.resolve("assets/minecraft")))
    }

    @Test
    fun `existing offline parent correction and centered base stay unchanged in both rail directions`() {
        val model = SignalPackLoader.load(directory.resolve("signal-packs")).models.getValue("jr_four_light:head").presentation
        val head = model.definition.parts().single() as ModelPart.Group
        // Preserve the previous offline transform only; this omits the client's extra ItemDisplay Yπ.
        assertEquals(2, head.children().size)
        assertEquals(1.5, head.pose().y())
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
                val rootRotation = quaternion(root)
                val headRotation = Quaterniond(rootRotation).mul(quaternion(head.pose()))
                assertVector(headRotation.transform(Vector3d(0.0, 0.0, -1.0)), -direction, 0.0, 0.0)
                val position = rootRotation.transform(Vector3d(head.pose().x(), head.pose().y(), head.pose().z()))
                    .add(root.x(), root.y(), root.z())
                // Vanilla model units centered at (8,8,8): the rebased bottom (8,-16,8) is -1.5m Y.
                val base = headRotation.transform(Vector3d(0.0, -1.5, 0.0)).add(position)
                assertVector(base, 15.0, 20.0, 30.0 + direction * side)
                val top = headRotation.transform(Vector3d(0.0, (26.0 - 8.0) / 16.0, 0.0)).add(position)
                assertEquals(2.625, top.y - base.y, 1e-10)
            }
        }
    }

    private fun json(path: Path): JsonObject = Files.newBufferedReader(path).use { JsonParser.parseReader(it).asJsonObject }

    private fun asset(key: String, kind: String, extension: String): Path {
        val (namespace, name) = key.split(':', limit = 2)
        val path = resourcePack.resolve("assets/$namespace/$kind/$name$extension")
        assertTrue(Files.isRegularFile(path), "Missing asset for $key: $path")
        return path
    }

    private fun quaternion(pose: ModelPose) = Quaterniond(pose.rotationX(), pose.rotationY(), pose.rotationZ(), pose.rotationW())

    private fun assertVector(value: Vector3d, x: Double, y: Double, z: Double) {
        assertEquals(x, value.x, 1e-10)
        assertEquals(y, value.y, 1e-10)
        assertEquals(z, value.z, 1e-10)
    }

    companion object {
        @JvmStatic @BeforeAll
        fun bootstrapPaperItems() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
