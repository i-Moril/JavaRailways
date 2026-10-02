package work.art1st.javarailways.bukkit.api.model

import org.bukkit.Material
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.render.model.ModelRuntimeTestSupport

class ModelDefinitionTest {
    @Test
    fun `value boundaries copy every caller list and expose unmodifiable collections`() {
        val frames = mutableListOf(frame(0.0, "Base"))
        val animation = ModelAnimation("Lamp", frames)
        val animations = mutableListOf(animation)
        val item = ModelPart.Item(ModelPose.IDENTITY, ModelScale.UNIT, appearance(), animations)
        val children = mutableListOf<ModelPart>(item)
        val group = ModelPart.Group(ModelPose.IDENTITY, children, emptyList())
        val roots = mutableListOf<ModelPart>(group)
        val definition = ModelDefinition(roots)
        frames.clear(); animations.clear(); children.clear(); roots.clear()
        assertEquals(1, definition.parts().size)
        assertEquals(1, group.children().size)
        assertEquals(1, item.animations().size)
        assertEquals(1, animation.frames().size)
        assertThrows(UnsupportedOperationException::class.java) { (definition.parts() as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (group.children() as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (item.animations() as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (animation.frames() as MutableList).clear() }
    }

    @Test
    fun `pose accepts a unit quaternion and rejects nonfinite or nonunit components`() {
        val half = Math.sqrt(0.5)
        assertEquals(half, ModelPose(1.0, 2.0, 3.0, 0.0, half, 0.0, half).rotationY())
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            for (axis in 0..6) {
                val values = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0).also { it[axis] = invalid }
                assertThrows(IllegalArgumentException::class.java) {
                    ModelPose(values[0], values[1], values[2], values[3], values[4], values[5], values[6])
                }
            }
        }
        assertThrows(IllegalArgumentException::class.java) { ModelPose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { ModelPose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 2.0) }
        assertThrows(IllegalArgumentException::class.java) { ModelPose(0.0, 0.0, 0.0, Double.MAX_VALUE, 0.0, 0.0, 1.0) }
    }

    @Test
    fun `item scale is finite nonnegative and allows zero on every axis`() {
        assertEquals(0.0, ModelScale(0.0, 0.0, 0.0).x())
        for (invalid in listOf(-0.1, Double.NaN, Double.POSITIVE_INFINITY)) for (axis in 0..2) {
            val values = doubleArrayOf(1.0, 1.0, 1.0).also { it[axis] = invalid }
            assertThrows(IllegalArgumentException::class.java) { ModelScale(values[0], values[1], values[2]) }
        }
    }

    @Test
    fun `item values validate material full keys precise CMD and brightness`() {
        assertEquals("javarailways:signals/lamp", appearance("javarailways:signals/lamp").itemModelKey())
        assertEquals(16777216, ItemAppearance(Material.PAPER, null, 16777216, null).customModelData())
        assertEquals(16777218, ItemAppearance(Material.PAPER, null, 16777218, null).customModelData())
        for (invalid in listOf(-1, 16777217, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { ItemAppearance(Material.PAPER, null, invalid, null) }
        }
        for (invalid in listOf("lamp", ":lamp", "addon:", "minecraft:addon:lamp", "ADDON:lamp", "addon:bad space")) {
            assertThrows(IllegalArgumentException::class.java) { appearance(invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) { ItemAppearance(Material.WATER, null, null, null) }
        assertThrows(IllegalArgumentException::class.java) { ItemAppearance(Material.LEGACY_STONE, null, null, null) }
        assertEquals(ItemAppearance.Brightness(0, 15), ItemAppearance.Brightness(0, 15))
        for ((block, sky) in listOf(-1 to 0, 16 to 0, 0 to -1, 0 to 16)) {
            assertThrows(IllegalArgumentException::class.java) { ItemAppearance.Brightness(block, sky) }
        }
    }

    @Test
    fun `animations require zero first finite increasing times and exact nonblank names`() {
        assertEquals(" Lamp ", ModelAnimation(" Lamp ", listOf(frame(0.0, " Green "))).name())
        for (name in listOf("", " \t")) {
            assertThrows(IllegalArgumentException::class.java) { ModelAnimation(name, listOf(frame(0.0, "ok"))) }
            assertThrows(IllegalArgumentException::class.java) { frame(0.0, name) }
        }
        assertThrows(IllegalArgumentException::class.java) { ModelAnimation("Lamp", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { ModelAnimation("Lamp", listOf(frame(1.0, "ok"))) }
        for (time in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            assertThrows(IllegalArgumentException::class.java) { frame(time, "bad") }
        }
        for (frames in listOf(listOf(frame(0.0, "a"), frame(0.0, "b")), listOf(frame(0.0, "a"), frame(2.0, "b"), frame(1.0, "c")))) {
            assertThrows(IllegalArgumentException::class.java) { ModelAnimation("Lamp", frames) }
        }
        assertThrows(IllegalArgumentException::class.java) { ModelAnimation("Lamp", listOf(frame(0.0, "a"), frame(1.0, "a"))) }
        assertEquals(2, ModelAnimation("Lamp", listOf(frame(0.0, "a"), frame(1e-12, "A"))).frames().size)
    }

    @Test
    fun `groups forbid item appearance and scale overlays and parts reject duplicate animation names`() {
        val animation = ModelAnimation("Lamp", listOf(frame(0.0, "a")))
        assertThrows(IllegalArgumentException::class.java) { ModelPart.Item(ModelPose.IDENTITY, ModelScale.UNIT, appearance(), listOf(animation, animation)) }
        assertThrows(IllegalArgumentException::class.java) { ModelPart.Group(ModelPose.IDENTITY, emptyList(), listOf(animation, animation)) }
        for (frame in listOf(
            ModelAnimation.Frame(0.0, "a", ModelPose.IDENTITY, ModelScale(0.0, 1.0, 1.0), null),
            ModelAnimation.Frame(0.0, "a", ModelPose.IDENTITY, ModelScale.UNIT, appearance())
        )) {
            assertThrows(IllegalArgumentException::class.java) { ModelPart.Group(ModelPose.IDENTITY, emptyList(), listOf(ModelAnimation("Lamp", listOf(frame)))) }
        }
    }

    @Test
    fun `same animation name across any tree parts requires identical exact scene sets`() {
        fun part(scene: String) = ModelPart.Item(ModelPose.IDENTITY, ModelScale.UNIT, appearance(), listOf(ModelAnimation("Lamp", listOf(frame(0.0, scene)))))
        assertEquals(2, ModelDefinition(listOf(part("Green"), part("Green"))).parts().size)
        assertThrows(IllegalArgumentException::class.java) { ModelDefinition(listOf(part("Green"), ModelPart.Group(ModelPose.IDENTITY, listOf(part("green")), emptyList()))) }
        assertEquals(0, ModelDefinition(emptyList()).parts().size)
    }

    private fun appearance(key: String? = null) = ItemAppearance(Material.PAPER, key, null, null)
    private fun frame(at: Double, scene: String?) = ModelAnimation.Frame(at, scene, ModelPose.IDENTITY, ModelScale.UNIT, null)

    companion object {
        @JvmStatic @BeforeAll fun bootstrap() = ModelRuntimeTestSupport.bootstrap()
    }
}
