package work.art1st.javarailways.bukkit.render.model

import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.key.Key
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World as BukkitWorld
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaterniond
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.model.*
import work.art1st.javarailways.bukkit.config.model.ModelTreeBuilder
import work.art1st.javarailways.bukkit.render.backend.DisplayRenderBackend
import work.art1st.javarailways.bukkit.render.backend.DisplayVisual
import work.art1st.javarailways.bukkit.render.backend.DisplayVisualContent
import work.art1st.javarailways.bukkit.render.backend.DisplayVisualDescriptor
import work.art1st.javarailways.core.physics.train.createTestWorld
import java.lang.reflect.Proxy

class OrdinaryModelTest {
    @Test
    fun `construction failures clean every returned visual including a failed root mount`() {
        for (failCreate in listOf(2, 3, null)) {
            val world = createTestWorld()
            val backend = RecordingBackend(failCreate = failCreate, failMount = failCreate == null)
            val bukkitWorld = Proxy.newProxyInstance(BukkitWorld::class.java.classLoader, arrayOf(BukkitWorld::class.java)) { _, method, _ ->
                when (method.name) { "toString" -> "PartialModelWorld"; else -> error("Unexpected call ${method.name}") }
            } as BukkitWorld
            try {
                val failure = assertThrows(work.art1st.javarailways.bukkit.render.backend.DisplayCleanupException::class.java) {
                    ModelTreeBuilder(world, backend, 1.0f).buildModel(
                        ModelDefinition(listOf(item(), item())), Location(bukkitWorld, 0.0, 64.0, 0.0)
                    )
                }
                assertTrue(failure.cleanup.toCompletableFuture().isDone)
                assertFalse(failure.cleanup.toCompletableFuture().isCompletedExceptionally)
                assertTrue(backend.visuals.isNotEmpty())
                assertTrue(backend.visuals.all { it.destroyCalls == 1 }, "all partially created visuals must be released exactly once")
            } finally {
                world.physicsCoordinator.close()
                (world.tickManager as AutoCloseable).close()
            }
        }
    }

    @Test
    fun `container local pose and overlay compose through root with leaf-only scale and zero smoothing`() {
        val half = Math.sqrt(0.5)
        val groupAnimation = ModelAnimation("Group", listOf(
            frame(0.0, "Base"), frame(1.0, "Moved", pose = pose(1.0, 0.0, 0.0, z = half, w = half))
        ))
        val itemAnimation = ModelAnimation("Item", listOf(
            frame(0.0, "Base"), frame(2.0, "Hidden", pose = pose(0.0, 2.0, 0.0), scale = ModelScale(0.0, 0.5, 2.0))
        ))
        val item = item(pose(1.0, 0.0, 0.0), ModelScale(2.0, 3.0, 4.0), listOf(itemAnimation))
        val group = ModelPart.Group(pose(2.0, 0.0, 0.0, z = half, w = half), listOf(item), listOf(groupAnimation))
        withModel(ModelDefinition(listOf(group))) { model, backend ->
            val rootPose = pose(100.0, 64.0, 200.0, z = half, w = half)
            assertTrue(model.submit(rootPose, emptyMap()))
            val visual = backend.items.single()
            assertVector(visual.transformation!!.translation, -1.0, 2.0, 0.0)
            assertVector(visual.transformation!!.scale, 2.0, 3.0, 4.0)
            assertQuaternion(Quaterniond().rotateZ(Math.PI), visual.transformation!!)
            assertEquals(100.0, backend.root.lastLocation!!.x)
            assertEquals(64.0, backend.root.lastLocation!!.y)
            assertTrue(backend.descriptors.all { it.interpolationDuration == 0 && it.teleportDuration == 0 })

            assertTrue(model.submit(rootPose, mapOf("Group" to "Moved", "Item" to "Hidden")))
            // Root 90deg + group base 90deg + group overlay 90deg; local child offset is (1,2,0).
            assertVector(visual.transformation!!.translation, 2.0, 2.0, 0.0)
            assertVector(visual.transformation!!.scale, 0.0, 1.5, 8.0)
            assertQuaternion(Quaterniond().rotateZ(3 * Math.PI / 2), visual.transformation!!)
            assertTrue(model.submit(rootPose, emptyMap()))
            assertVector(visual.transformation!!.translation, -1.0, 2.0, 0.0)
            assertVector(visual.transformation!!.scale, 2.0, 3.0, 4.0)
        }
    }

    @Test
    fun `complete appearance clears old keys CMD and brightness and empty selection restores baseline`() {
        val baseline = ItemAppearance(Material.PAPER, "javarailways:signals/base", 25, null)
        val changed = ItemAppearance(Material.DIAMOND, "another:signals/green", 16777216, ItemAppearance.Brightness(7, 12))
        val bare = ItemAppearance(Material.DIAMOND, null, null, null)
        val animation = ModelAnimation("Lamp", listOf(
            frame(0.0, "Base"), frame(1.0, "Green", appearance = changed),
            frame(2.0, "Bare", appearance = bare), frame(3.0, "Baseline")
        ))
        withModel(ModelDefinition(listOf(item(animations = listOf(animation), appearance = baseline)))) { model, backend ->
            val visual = backend.items.single()
            model.submit(ModelPose.IDENTITY, emptyMap())
            assertAppearance(visual.itemStack!!, Material.PAPER, "javarailways:signals/base", 25)
            assertNull(visual.brightness)

            model.submit(ModelPose.IDENTITY, mapOf("Lamp" to "Green"))
            assertAppearance(visual.itemStack!!, Material.DIAMOND, "another:signals/green", 16777216)
            assertEquals(ItemAppearance.Brightness(7, 12), visual.brightness)

            model.submit(ModelPose.IDENTITY, mapOf("Lamp" to "Bare"))
            assertAppearance(visual.itemStack!!, Material.DIAMOND, null, null)
            assertNull(visual.brightness, "natural light must be cleared, not set to (0,0)")
            assertEquals(1, visual.clearBrightnessCalls)

            model.submit(ModelPose.IDENTITY, mapOf("Lamp" to "Green"))
            model.submit(ModelPose.IDENTITY, mapOf("Lamp" to "Baseline"))
            assertAppearance(visual.itemStack!!, Material.PAPER, "javarailways:signals/base", 25)
            assertNull(visual.brightness, "null frame appearance chooses the baseline")

            model.submit(ModelPose.IDENTITY, mapOf("Lamp" to "Green"))
            model.submit(ModelPose.IDENTITY, emptyMap())
            assertAppearance(visual.itemStack!!, Material.PAPER, "javarailways:signals/base", 25)
            assertNull(visual.brightness)
        }
    }

    @Test
    fun `AIR scenes remain empty with optional item values and restore the complete baseline`() {
        val baseline = ItemAppearance(Material.PAPER, "javarailways:signals/base", 25, ItemAppearance.Brightness(15, 4))
        val empty = ItemAppearance(Material.AIR, null, null, null)
        val emptyWithValues = ItemAppearance(Material.AIR, "another:signals/empty", 42, ItemAppearance.Brightness(7, 12))
        val animation = ModelAnimation("Lamp", listOf(
            frame(0.0, "Empty", appearance = empty), frame(1.0, "EmptyWithValues", appearance = emptyWithValues)
        ))
        withModel(ModelDefinition(listOf(item(animations = listOf(animation), appearance = baseline)))) { model, backend ->
            val visual = backend.items.single()
            model.submit(ModelPose.IDENTITY, mapOf("Lamp" to "Empty"))
            assertTrue(visual.itemStack!!.isEmpty)
            assertAppearance(visual.itemStack!!, Material.AIR, null, null)
            assertNull(visual.brightness)

            model.submit(ModelPose.IDENTITY, mapOf("Lamp" to "EmptyWithValues"))
            assertTrue(visual.itemStack!!.isEmpty)
            // Paper does not retain item components on AIR; display brightness is independent.
            assertAppearance(visual.itemStack!!, Material.AIR, null, null)
            assertEquals(ItemAppearance.Brightness(7, 12), visual.brightness)

            model.submit(ModelPose.IDENTITY, emptyMap())
            assertAppearance(visual.itemStack!!, Material.PAPER, "javarailways:signals/base", 25)
            assertEquals(ItemAppearance.Brightness(15, 4), visual.brightness)
        }
    }

    @Test
    fun `reset restores baseline brightness and switching to another part resets former hits`() {
        val baseline = ItemAppearance(Material.PAPER, null, null, ItemAppearance.Brightness(15, 4))
        val dark = ItemAppearance(Material.PAPER, "signals:dark", 42, null)
        val first = item(animations = listOf(ModelAnimation("First", listOf(frame(0.0, "On", pose = pose(4.0), appearance = dark)))), appearance = baseline)
        val second = item(animations = listOf(ModelAnimation("Second", listOf(frame(0.0, "On", pose = pose(2.0))))))
        withModel(ModelDefinition(listOf(first, second))) { model, backend ->
            model.submit(ModelPose.IDENTITY, mapOf("First" to "On"))
            assertNull(backend.items[0].brightness)
            assertVector(backend.items[0].transformation!!.translation, 4.0, 0.0, 0.0)
            model.submit(ModelPose.IDENTITY, mapOf("Second" to "On"))
            assertAppearance(backend.items[0].itemStack!!, Material.PAPER, null, null)
            assertEquals(ItemAppearance.Brightness(15, 4), backend.items[0].brightness)
            assertVector(backend.items[0].transformation!!.translation, 0.0, 0.0, 0.0)
            assertVector(backend.items[1].transformation!!.translation, 2.0, 0.0, 0.0)
        }
    }

    @Test
    fun `same exact animation broadcasts per-part frames and samples markers without time epsilon or aliases`() {
        fun lamp(distance: Double, time: Double) = item(animations = listOf(ModelAnimation("Lamp", listOf(
            frame(0.0, "o"), frame(time, "Open", pose = pose(distance)), frame(time * 2, "open", pose = pose(distance + 1))
        ))))
        withModel(ModelDefinition(listOf(lamp(2.0, 1e-12), lamp(5.0, 7.0)))) { model, backend ->
            model.submit(ModelPose.IDENTITY, mapOf("Lamp" to "Open"))
            assertVector(backend.items[0].transformation!!.translation, 2.0, 0.0, 0.0)
            assertVector(backend.items[1].transformation!!.translation, 5.0, 0.0, 0.0)
            model.submit(ModelPose.IDENTITY, mapOf("Lamp" to "o"))
            assertVector(backend.items[0].transformation!!.translation, 0.0, 0.0, 0.0)
            model.submit(ModelPose.IDENTITY, mapOf("Lamp" to "open"))
            assertVector(backend.items[0].transformation!!.translation, 3.0, 0.0, 0.0)
            val before = backend.calls
            assertThrows(IllegalArgumentException::class.java) { model.submit(ModelPose.IDENTITY, mapOf("lamp" to "open")) }
            assertThrows(IllegalArgumentException::class.java) { model.submit(ModelPose.IDENTITY, mapOf("Lamp" to "OPEN")) }
            assertEquals(before, backend.calls)
        }
    }

    @Test
    fun `unknown selections and competing animations reject the entire state before mutation`() {
        val first = ModelAnimation("First", listOf(frame(0.0, "On", pose = pose(1.0))))
        val second = ModelAnimation("Second", listOf(frame(0.0, "On", pose = pose(2.0))))
        withModel(ModelDefinition(listOf(item(animations = listOf(first, second)), item(animations = listOf(first))))) { model, backend ->
            model.submit(ModelPose.IDENTITY, mapOf("First" to "On"))
            val before = backend.calls
            val conflicting = assertThrows(IllegalArgumentException::class.java) {
                model.submit(pose(100.0), mapOf("First" to "On", "Second" to "On"))
            }
            assertTrue(conflicting.message!!.contains("Part 0"))
            for (scenes in listOf(mapOf("First" to "Missing"), mapOf("First" to "On", "Missing" to "On"))) {
                assertThrows(IllegalArgumentException::class.java) { model.submit(pose(100.0), scenes) }
            }
            assertEquals(before, backend.calls)
            assertVector(backend.items[0].transformation!!.translation, 1.0, 0.0, 0.0)
            assertFalse(model.submit(ModelPose.IDENTITY, mapOf("First" to "On")), "rejected state must retain prior complete selection")
        }
    }

    @Test
    fun `unchanged copied selection does no entity work while root movement still applies`() {
        val animation = ModelAnimation("Lamp", listOf(frame(0.0, "On", pose = pose(1.0))))
        withModel(ModelDefinition(listOf(item(animations = listOf(animation))))) { model, backend ->
            val caller = mutableMapOf("Lamp" to "On")
            assertTrue(model.submit(ModelPose.IDENTITY, caller))
            caller.clear()
            val before = backend.calls
            assertFalse(model.submit(ModelPose.IDENTITY, mapOf("Lamp" to "On")))
            assertEquals(before, backend.calls)
            val transforms = backend.items.single().transformationCalls
            assertTrue(model.submit(pose(5.0, 6.0, 7.0), mapOf("Lamp" to "On")))
            assertEquals(transforms, backend.items.single().transformationCalls, "translation-only move uses the shared root")
            assertEquals(5.0, backend.root.lastLocation!!.x)
            assertEquals(6.0, backend.root.lastLocation!!.y)
            assertEquals(7.0, backend.root.lastLocation!!.z)
            assertTrue(model.submit(pose(5.0, 6.0, 7.0, z = 1.0, w = 0.0), mapOf("Lamp" to "On")))
            assertVector(backend.items.single().transformation!!.translation, -1.0, 0.0, 0.0)
            assertFalse(model.submit(pose(5.0, 6.0, 7.0, z = 1.0, w = 0.0), mapOf("Lamp" to "On")))
        }
    }

    private fun item(pose: ModelPose = ModelPose.IDENTITY, scale: ModelScale = ModelScale.UNIT,
                     animations: List<ModelAnimation> = emptyList(), appearance: ItemAppearance = ItemAppearance(Material.PAPER, null, null, null)) =
        ModelPart.Item(pose, scale, appearance, animations)

    private fun frame(time: Double, scene: String, pose: ModelPose = ModelPose.IDENTITY,
                      scale: ModelScale = ModelScale.UNIT, appearance: ItemAppearance? = null) =
        ModelAnimation.Frame(time, scene, pose, scale, appearance)

    private fun pose(x: Double = 0.0, y: Double = 0.0, zPosition: Double = 0.0, z: Double = 0.0, w: Double = 1.0) =
        ModelPose(x, y, zPosition, 0.0, 0.0, z, w)

    private fun assertVector(actual: org.joml.Vector3f, x: Double, y: Double, z: Double) {
        assertEquals(x, actual.x().toDouble(), 1e-5); assertEquals(y, actual.y().toDouble(), 1e-5); assertEquals(z, actual.z().toDouble(), 1e-5)
    }

    private fun assertQuaternion(expected: Quaterniond, actual: Transformation) {
        val rotation = actual.rightRotation
        assertEquals(expected.x(), rotation.x().toDouble(), 1e-6); assertEquals(expected.y(), rotation.y().toDouble(), 1e-6)
        assertEquals(expected.z(), rotation.z().toDouble(), 1e-6); assertEquals(expected.w(), rotation.w().toDouble(), 1e-6)
    }

    private fun assertAppearance(item: ItemStack, material: Material, key: String?, cmd: Int?) {
        assertEquals(material, item.type)
        if (key == null) assertFalse(item.isDataOverridden(DataComponentTypes.ITEM_MODEL))
        else assertEquals(Key.key(key), item.getData(DataComponentTypes.ITEM_MODEL))
        if (cmd == null) assertFalse(item.isDataOverridden(DataComponentTypes.CUSTOM_MODEL_DATA))
        else assertEquals(listOf(cmd.toFloat()), item.getData(DataComponentTypes.CUSTOM_MODEL_DATA)!!.floats())
    }

    private fun withModel(definition: ModelDefinition, block: (OrdinaryModel, RecordingBackend) -> Unit) {
        val world = createTestWorld()
        val backend = RecordingBackend()
        val bukkitWorld = Proxy.newProxyInstance(BukkitWorld::class.java.classLoader, arrayOf(BukkitWorld::class.java)) { _, method, _ ->
            when (method.name) { "toString" -> "ModelTestWorld"; else -> error("Unexpected Bukkit world call: ${method.name}") }
        } as BukkitWorld
        var model: OrdinaryModel? = null
        try {
            model = ModelTreeBuilder(world, backend, 1.0f).buildModel(definition, Location(bukkitWorld, 100.0, 64.0, 200.0))
            block(model, backend)
        } finally {
            model?.destroy()
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    private class RecordingBackend(val failCreate: Int? = null, val failMount: Boolean = false) : DisplayRenderBackend {
        val descriptors = mutableListOf<DisplayVisualDescriptor>()
        val visuals = mutableListOf<RecordingVisual>()
        val root get() = visuals.last()
        val items get() = visuals.dropLast(1)
        val calls get() = visuals.sumOf { it.calls }
        override fun create(world: BukkitWorld, descriptor: DisplayVisualDescriptor): DisplayVisual {
            assertSame(world, descriptor.location.world)
            descriptors += descriptor
            check(descriptors.size != failCreate) { "Scripted backend creation failure" }
            return RecordingVisual((descriptor.content as DisplayVisualContent.Item).itemStack.clone(), failMount).also { visuals += it }
        }
    }

    private class RecordingVisual(@JvmField var itemStack: ItemStack?, val failMount: Boolean = false) : DisplayVisual {
        @JvmField var transformation: Transformation? = null
        var lastLocation: Location? = null
        var brightness: ItemAppearance.Brightness? = null
        var transformationCalls = 0
        var clearBrightnessCalls = 0
        var calls = 0
        var destroyCalls = 0
        override fun showTo(player: Player) { calls++ }
        override fun hideFrom(player: Player) { calls++ }
        override fun teleport(location: Location) { lastLocation = location.clone(); calls++ }
        override fun setTransformation(transformation: Transformation) { this.transformation = transformation; transformationCalls++; calls++ }
        override fun setText(text: net.kyori.adventure.text.Component) { throw IllegalArgumentException("This test visual does not support text") }
        override fun setItemStack(itemStack: ItemStack) { this.itemStack = itemStack.clone(); calls++ }
        override fun setBrightness(blockLight: Int, skyLight: Int) { brightness = ItemAppearance.Brightness(blockLight, skyLight); calls++ }
        override fun clearBrightness() { brightness = null; clearBrightnessCalls++; calls++ }
        override fun addPassenger(passenger: DisplayVisual): Boolean { check(!failMount) { "Scripted mount failure" }; return true }
        override fun destroy(): java.util.concurrent.CompletionStage<Void> {
            destroyCalls++; calls++
            return java.util.concurrent.CompletableFuture.completedFuture(null)
        }
    }

    companion object {
        @JvmStatic @BeforeAll fun bootstrap() = ModelRuntimeTestSupport.bootstrap()
    }
}
