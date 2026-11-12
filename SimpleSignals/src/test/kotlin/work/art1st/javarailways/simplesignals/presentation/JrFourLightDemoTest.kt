package work.art1st.javarailways.simplesignals.presentation

import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.bukkit.Material
import org.joml.Quaterniond
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.model.ModelPart
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.bukkit.api.model.ModelSelectionBox
import work.art1st.javarailways.bukkit.api.model.ModelScale
import work.art1st.javarailways.bukkit.api.model.ItemAppearance
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simplesignals.signalpack.SignalPackLoader
import java.nio.file.Path
import java.util.UUID

class JrFourLightDemoTest {
    private val directory = Path.of(requireNotNull(javaClass.getResource("/examples/jr-four-light")).toURI())
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
        assertEquals(listOf(ModelSelectionBox(-0.625, 0.0, -0.3125, 0.625, 2.625, 0.125)),
            model.definition.selectionBoxes())
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
        assertEquals(2.0, model.railPlacementPresets.getValue("left").adjustment.x())
        assertEquals(-2.0, model.railPlacementPresets.getValue("right").adjustment.x())
        model.railPlacementPresets.values.forEach {
            assertEquals(false, it.followRailTilt)
            assertEquals(1.0, it.adjustment.rotationW()) // No second facing rotation in the preset.
        }
    }

    @Test
    fun `client item rotation faces approaching trains and preserves the base for rail and ground placement`() {
        val model = SignalPackLoader.load(directory.resolve("signal-packs")).models.getValue("jr_four_light:head").presentation
        val head = model.definition.parts().single() as ModelPart.Group
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
                // Client ItemDisplayRenderer multiplies local Yπ after the display transformation.
                val headRotation = Quaterniond(rootRotation).mul(quaternion(head.pose())).rotateY(Math.PI)
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
        // Ground yaw=0 aligns model +Z with world +Z; its front faces world -Z.
        val groundRotation = Quaterniond()
        val ground = ModelPlacement.Ground(ModelPose(11.0, 63.5, -3.5,
            groundRotation.x, groundRotation.y, groundRotation.z, groundRotation.w))
        val groundRoot = ModelPlacementCalculator.calculate(ground)
        val renderedRotation = quaternion(groundRoot).mul(quaternion(head.pose())).rotateY(Math.PI)
        assertVector(renderedRotation.transform(Vector3d(0.0, 0.0, -1.0)), 0.0, 0.0, -1.0)
        val groundHead = quaternion(groundRoot).transform(Vector3d(head.pose().x(), head.pose().y(), head.pose().z()))
            .add(groundRoot.x(), groundRoot.y(), groundRoot.z())
        assertVector(renderedRotation.transform(Vector3d(0.0, -1.5, 0.0)).add(groundHead), 11.0, 63.5, -3.5)
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
