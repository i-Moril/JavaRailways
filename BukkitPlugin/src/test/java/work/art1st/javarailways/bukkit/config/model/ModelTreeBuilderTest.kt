package work.art1st.javarailways.bukkit.config.model

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaterniond
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import work.art1st.javarailways.bukkit.render.backend.DisplayRenderBackend
import work.art1st.javarailways.bukkit.render.backend.DisplayVisualContent
import work.art1st.javarailways.bukkit.render.backend.DisplayVisualDescriptor
import work.art1st.javarailways.bukkit.render.model.ModelRuntimeTestSupport
import work.art1st.javarailways.bukkit.render.backend.DisplayVisual
import work.art1st.javarailways.bukkit.render.model.AbstractModel
import work.art1st.javarailways.bukkit.render.model.CompactTransform
import work.art1st.javarailways.bukkit.render.model.CompositeModel
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.train.createTestWorld
import java.lang.reflect.Proxy
import java.nio.file.Path

class ModelTreeBuilderTest {
    @Test
    fun `loaded YAML applies inline transforms once and preserves import transforms`(@TempDir directory: Path) = withWorld { world ->
        val namespace = directory.resolve("train").toFile().apply { mkdirs() }
        namespace.resolve("body.yml").writeText("""
            config-version: 1
            name: body
            type: dummy
            offset: {x: 2.0}
            rotation: {z: 10.0}
            parts:
              - config-version: 1
                name: inline
                type: dummy
                offset: {x: 1.0, y: 3.0}
                rotation: {z: 30.0}
              - import: child
                offset: {y: 4.0}
                rotation: {z: 20.0}
        """.trimIndent())
        namespace.resolve("child.yml").writeText("""
            config-version: 1
            name: child
            type: dummy
            offset: {x: 5.0}
            rotation: {z: 40.0}
        """.trimIndent())
        val registry = PartRegistry(directory.toFile()).apply { load() }
        val builder = ModelTreeBuilder(world, registry) { RecordingRoot() }
        val model = builder.buildCarBody(
            CarDefinition("train/car", bodyParts = listOf(PartRefEntry.import("body"))),
            Location(null, 0.0, 0.0, 0.0)
        )
        try {
            val nodes = nodes(model)
            assertEquals(3, nodes.size)
            assertEquals(Vector3d(2.0, 0.0, 0.0), nodes[0].state.localTransform.position)
            assertEquals(Vector3d(1.0, 3.0, 0.0), nodes[1].state.localTransform.position)
            assertTrue(Quaterniond().rotateZ(Math.toRadians(30.0)).equals(nodes[1].state.localTransform.rotation, 1e-9))
            assertEquals(Vector3d(5.0, 4.0, 0.0), nodes[2].state.localTransform.position)
            assertTrue(Quaterniond().rotateZ(Math.toRadians(60.0)).equals(nodes[2].state.localTransform.rotation, 1e-9))
        } finally {
            model.destroy()
        }
    }

    @Test
    fun `car and bogie roots and items retain three tick smoothing and legacy item keys`() = withWorld { world ->
        ModelRuntimeTestSupport.bootstrap()
        val part = PartDefinition("train/body", PartType.ITEM_DISPLAY, item = PartItemConfig("paper", "train/body", 42))
        val registry = SnapshotPartRegistry(mapOf(part.name to part))
        val descriptors = mutableListOf<DisplayVisualDescriptor>()
        val backend = object : DisplayRenderBackend {
            override fun create(world: org.bukkit.World, descriptor: DisplayVisualDescriptor): DisplayVisual {
                descriptors += descriptor
                return RecordingRoot()
            }
        }
        val bukkitWorld = Proxy.newProxyInstance(org.bukkit.World::class.java.classLoader, arrayOf(org.bukkit.World::class.java)) { _, method, _ ->
            if (method.name == "toString") "TrainModelTestWorld" else error("Unexpected call: ${method.name}")
        } as org.bukkit.World
        val builder = ModelTreeBuilder(world, registry, null, backend, 1f)
        val anchor = Location(bukkitWorld, 100.0, 64.0, 200.0)
        val refs = listOf(PartRefEntry.import("body"))
        val car = builder.buildCarBody(CarDefinition("train/car", bodyParts = refs), anchor)
        val bogie = builder.buildBogie(BogieDefinition(parts = refs), "train", anchor)
        try {
            assertEquals(4, descriptors.size)
            assertTrue(descriptors.all { it.interpolationDuration == 3 && it.teleportDuration == 3 })
            val item = (descriptors.first().content as DisplayVisualContent.Item).itemStack
            assertEquals(Material.PAPER, item.type)
            assertEquals(net.kyori.adventure.key.Key.key("minecraft:train/body"), item.getData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL))
        } finally { car.destroy(); bogie.destroy() }
    }

    @Test
    fun `car body resolves imported and inline hierarchy with local transforms and animation`() = withWorld { world ->
        val child = PartDefinition(
            "shared/child", PartType.DUMMY,
            offset = Vec3Config(x = 1.0),
            rotation = Vec3Config(z = 30.0),
            animations = listOf(PartAnimationConfig("shape", keyframes = listOf(
                PartAnimationKeyframeConfig(0.0, "base"),
                PartAnimationKeyframeConfig(1.0, "raised", offset = Vec3Config(y = 2.0))
            )))
        )
        val inline = PartDefinition("shared/group", PartType.DUMMY, parts = listOf(
            PartRefEntry.import("child", Vec3Config(y = 3.0), Vec3Config(z = 60.0))
        ))
        val root = PartDefinition("ordinary/root", PartType.DUMMY, offset = Vec3Config(x = 4.0), parts = listOf(
            PartRefEntry(inline.name, offset = Vec3Config(z = 5.0), inline = inline)
        ))
        val registry = SnapshotPartRegistry(mapOf(root.name to root, child.name to child))
        val roots = mutableListOf<Location>()
        val builder = ModelTreeBuilder(world, registry) { location ->
            roots += location.clone()
            RecordingRoot()
        }
        val anchor = Location(null, 100.0, 64.0, 200.0)
        val model = builder.buildCarBody(CarDefinition("ordinary/car", bodyParts = listOf(PartRefEntry.import("root", Vec3Config(x = 2.0)))), anchor)
        try {
            val nodes = nodes(model)
            assertEquals(3, nodes.size)
            assertTrue(nodes.all { it is DummyModel })
            assertSame(nodes[1], nodes[0].attachedModels.single())
            assertSame(nodes[2], nodes[1].attachedModels.single())
            assertEquals(Vector3d(6.0, 0.0, 0.0), nodes[0].state.localTransform.position)
            assertEquals(Vector3d(0.0, 0.0, 5.0), nodes[1].state.localTransform.position)
            assertEquals(Vector3d(1.0, 3.0, 0.0), nodes[2].state.localTransform.position)
            assertTrue(Quaterniond().rotateZ(Math.PI / 2).equals(nodes[2].state.localTransform.rotation, 1e-9))
            assertEquals(listOf(anchor), roots)
            assertTrue(model.setAnimationScenes(mapOf("shape" to "raised")))
            assertEquals(mapOf("shape" to "raised"), model.collectHeldAnimationScenes())
            nodes[0].updateState(CompactTransform(), CompactTransform(), Vector3d())
            assertEquals(Vector3d(7.0, 5.0, 5.0), nodes[2].state.position)
        } finally {
            model.destroy()
        }
    }

    @Test
    fun `car body keeps its hierarchy separate from live bogie offset and wheel assembly`() = withWorld { world ->
        val body = PartDefinition("train/body", PartType.DUMMY, offset = Vec3Config(y = 1.0))
        val bogie = PartDefinition("train/bogie", PartType.DUMMY)
        val wheel = PartDefinition("train/wheel", PartType.DUMMY, offset = Vec3Config(z = 2.0))
        val registry = SnapshotPartRegistry(listOf(body, bogie, wheel).associateBy { it.name })
        val roots = mutableListOf<Location>()
        val builder = ModelTreeBuilder(world, registry) { location ->
            roots += location.clone()
            RecordingRoot()
        }
        val bogieDef = BogieDefinition(
            offset = Vec3Config(0.5, -0.5, 8.0),
            parts = listOf(PartRefEntry.import("bogie")),
            wheelParts = listOf(PartRefEntry.import("wheel"))
        )
        val carDef = CarDefinition("train/car", bodyParts = listOf(PartRefEntry.import("body")), bogies = listOf(bogieDef))
        val bodyAnchor = Location(null, 100.0, 65.0, 200.0)
        val bogieAnchor = Location(null, 100.0, 64.0, 208.0)
        val carModel = builder.buildCarBody(carDef, bodyAnchor)
        val bogieModel = builder.buildBogie(bogieDef, "train", bogieAnchor)
        try {
            assertEquals(1, nodes(carModel).size, "body construction must not attach bogies")
            assertEquals(Vector3d(0.0, 1.0, 0.0), nodes(carModel).single().state.localTransform.position)
            val bogieNodes = nodes(bogieModel)
            assertEquals(3, bogieNodes.size)
            assertEquals(2, bogieNodes[0].attachedModels.size)
            assertEquals(Vector3d(0.5, -0.5, 0.0), bogieNodes[0].state.localTransform.position,
                "live bogie location already includes the longitudinal offset")
            assertEquals(Vector3d(0.0, 0.0, 2.0), bogieNodes[2].state.localTransform.position)
            assertEquals(listOf(bodyAnchor, bogieAnchor), roots)
        } finally {
            carModel.destroy()
            bogieModel.destroy()
        }
    }

    @Test
    fun `partial child sibling and root failures release every created visual once`() = withWorld { world ->
        ModelRuntimeTestSupport.bootstrap()
        val bukkitWorld = Proxy.newProxyInstance(org.bukkit.World::class.java.classLoader, arrayOf(org.bukkit.World::class.java)) { _, method, _ ->
            if (method.name == "toString") "CleanupWorld" else error("Unexpected call: ${method.name}")
        } as org.bukkit.World
        val anchor = Location(bukkitWorld, 0.0, 0.0, 0.0)
        val item = PartDefinition("train/item", PartType.ITEM_DISPLAY, item = PartItemConfig("paper"))
        val invalidParent = PartDefinition("train/parent", PartType.ITEM_DISPLAY, parts = listOf(PartRefEntry.import("item")))
        for (scenario in listOf("sibling", "parent", "root", "mount")) {
            val visuals = mutableListOf<RecordingRoot>()
            val original = IllegalStateException("root failure")
            val backend = object : DisplayRenderBackend {
                override fun create(world: org.bukkit.World, descriptor: DisplayVisualDescriptor): DisplayVisual {
                    if (scenario == "root" && visuals.size == 1) throw original
                    return RecordingRoot(if (scenario == "mount" && visuals.size == 1) original else null).also(visuals::add)
                }
            }
            val builder = ModelTreeBuilder(world, SnapshotPartRegistry(listOf(item, invalidParent).associateBy { it.name }), null, backend, 1f)
            val refs = when (scenario) {
                "sibling" -> listOf(PartRefEntry.import("item"), PartRefEntry.import("missing"))
                "parent" -> listOf(PartRefEntry.import("parent"))
                else -> listOf(PartRefEntry.import("item"))
            }
            val failure = org.junit.jupiter.api.Assertions.assertThrows(work.art1st.javarailways.bukkit.render.backend.DisplayCleanupException::class.java) {
                builder.buildCarBody(CarDefinition("train/car", bodyParts = refs), anchor)
            }
            failure.cleanup.toCompletableFuture().join()
            assertTrue(visuals.isNotEmpty())
            assertTrue(visuals.all { it.destroyCalls == 1 }, scenario)
            if (scenario == "root" || scenario == "mount") assertSame(original, failure.cause)
        }
    }

    private fun nodes(model: CompositeModel): List<AbstractModel> = buildList {
        model.forEachModelRecursive { add(it) }
    }

    private fun withWorld(block: (CoreWorld) -> Unit) {
        val world = createTestWorld()
        try {
            block(world)
        } finally {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }

    private class RecordingRoot(private val mountFailure: Throwable? = null) : DisplayVisual {
        var destroyCalls = 0
        override fun showTo(player: Player) = Unit
        override fun hideFrom(player: Player) = Unit
        override fun teleport(location: Location) = Unit
        override fun setTransformation(transformation: Transformation) = Unit
        override fun setItemStack(itemStack: ItemStack) = Unit
        override fun setBrightness(blockLight: Int, skyLight: Int) = Unit
        override fun clearBrightness() = Unit
        override fun addPassenger(passenger: DisplayVisual): Boolean { mountFailure?.let { throw it }; return true }
        override fun destroy(): java.util.concurrent.CompletionStage<Void> { destroyCalls++; return java.util.concurrent.CompletableFuture.completedFuture(null) }
    }
}
