package work.art1st.javarailways.bukkit.render.model

import kotlinx.serialization.json.JsonObject
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.internal.DefaultPartExtensionService
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.bukkit.api.model.extension.*
import work.art1st.javarailways.bukkit.config.model.*
import work.art1st.javarailways.bukkit.render.backend.*
import work.art1st.javarailways.bukkit.util.EntityCleanupDispatcher
import work.art1st.javarailways.bukkit.util.PlatformDispatcher
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.api.util.DestructionSteps
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ExtensionPartModelTest {
    @Test fun `late frame mounts on current root with held group transform and replays child before parent passenger packet`() = fixture { f ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val registration = f.register { entered.countDown(); release.await(); f.frame() }
        val group = f.group()
        val composite = f.composite(group)
        try {
            composite.setAnimationScenes(mapOf("position" to "raised"))
            composite.updateState(CompactTransform(position = Vector3d(1.0, 0.0, 0.0)))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            composite.showTo(f.player)
            f.calls.clear()
            composite.updateRootPosition(Vector3d(50.0, 0.0, 0.0))
            release.countDown()
            f.awaitApplied(composite, 50.0)
            assertEquals(50.0, f.descriptors.single().location.x)
            assertEquals(5f, f.visuals.single().transformationValue!!.translation.x())
            assertEquals(1f, f.visuals.single().transformationValue!!.translation.y())
            assertFalse(f.calls.contains("text-show"))
            f.dispatcher.drain()
            val childShow = f.calls.indexOf("text-show")
            val parentPacket = f.calls.lastIndexOf("root-passengers")
            assertTrue(childShow >= 0 && parentPacket > childShow)
            val traversed = mutableListOf<AbstractModel>(); composite.forEachModelRecursive(traversed::add)
            assertEquals(listOf(group, f.child), traversed, "generated text must not change behavior animation slot ordering")
            val close = registration.closeAsync()
            assertEquals(1, f.visuals.single().destroys)
            assertEquals(0, f.child.destroyed)
            assertEquals(0, f.root.destroys)
            close.toCompletableFuture().join()
            composite.updateRootPosition(Vector3d(60.0, 0.0, 0.0))
            assertEquals(1, f.descriptors.size)
        } finally { release.countDown(); composite.destroy().toCompletableFuture().join(); registration.close() }
    }

    @Test fun `removed viewer membership suppresses queued late show and owner close never removes ordinary children`() = fixture { f ->
        val registration = f.register { f.frame() }
        val composite = f.composite(f.group())
        try {
            composite.showTo(f.player)
            composite.updateState(CompactTransform())
            f.awaitApplied(composite)
            assertTrue(f.dispatcher.actions.isNotEmpty())
            composite.hideFrom(f.player)
            f.calls.clear()
            f.dispatcher.drain()
            assertFalse(f.calls.contains("text-show"))
            registration.closeAsync().toCompletableFuture().join()
            assertEquals(0, f.child.destroyed)
        } finally { composite.destroy().toCompletableFuture().join(); registration.close() }
    }

    @Test fun `same UUID replacement viewer gets a new token and stale player hide cannot revoke it`() = fixture { f ->
        val registration = f.register { f.frame() }
        val composite = f.composite(f.group())
        val replacement = proxy(Player::class.java) { name, _ -> if (name == "getUniqueId") VIEWER else error(name) }
        try {
            composite.showTo(f.player)
            composite.updateState(CompactTransform())
            f.awaitApplied(composite)
            // Replace membership before the old queued show runs. Direct group hide avoids the
            // pre-existing composite/root UUID audience policy outside this attachment boundary.
            val group = mutableListOf<ExtensionPartModel>()
            composite.forEachModelRecursive { if (it is ExtensionPartModel) group += it }
            group.single().showVisualsTo(replacement)
            val beforeStaleHide = f.calls.toList()
            group.single().hideVisualsFrom(f.player)
            assertEquals(beforeStaleHide, f.calls)
            f.calls.clear()
            f.dispatcher.drain()
            assertFalse(f.calls.contains("text-show"), "old viewer token must not expose anything")
            group.single().showVisualsTo(replacement)
            assertTrue(f.calls.contains("text-show"), "stale hide must leave replacement membership active")
        } finally { registration.close(); composite.destroy().toCompletableFuture().join() }
    }

    @Test fun `destroy before worker completion rejects late frame and replacement requires a new group`() = fixture { f ->
        val oldGroup = f.group() // Provider not installed yet: permanently a normal group for this build.
        val oldComposite = f.composite(oldGroup)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val registration = f.register { entered.countDown(); while (true) try { release.await(); break } catch (_: InterruptedException) { }; f.frame() }
        oldComposite.updateState(CompactTransform())
        assertEquals(1L, entered.count)
        oldComposite.destroy().toCompletableFuture().join()
        val composite = f.composite(f.group())
        try {
            composite.updateState(CompactTransform())
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            composite.destroy().toCompletableFuture().join()
            release.countDown()
            composite.updateRootPosition(Vector3d(99.0, 0.0, 0.0))
            assertTrue(f.descriptors.isEmpty())
        } finally { release.countDown(); registration.close() }
    }

    @Test fun `partial create or mount failure retains group and close waits for real rollback`() {
        for (failMount in listOf(false, true)) fixture { f ->
            val cleanup = CompletableFuture<Void>()
            f.pendingCleanup = cleanup
            f.failCreateAt = if (failMount) -1 else 2
            f.root.failMount = failMount
            val registration = f.register { PartExtensionFrame(listOf(f.frame().texts.single(), f.frame().texts.single())) }
            val composite = f.composite(f.group())
            try {
                composite.updateState(CompactTransform())
                f.awaitAttempted(composite)
                assertEquals(1, f.visuals.single().destroys)
                val close = registration.closeAsync()
                assertFalse(close.toCompletableFuture().isDone)
                assertEquals(0, f.child.destroyed)
                cleanup.complete(null)
                close.toCompletableFuture().join()
            } finally { cleanup.complete(null); registration.close(); composite.destroy().toCompletableFuture().join() }
        }
    }

    @Test fun `reentrant owner retirement during create cannot finish before its returned visual is rolled back`() = fixture { f ->
        val cleanup = CompletableFuture<Void>(); f.pendingCleanup = cleanup
        val registration = f.register { f.frame() }
        var close: CompletionStage<Void>? = null
        f.onCreate = { close = registration.closeAsync(); assertFalse(close!!.toCompletableFuture().isDone) }
        val composite = f.composite(f.group())
        try {
            composite.updateState(CompactTransform())
            f.awaitAttempted(composite)
            assertEquals(1, f.visuals.single().destroys)
            assertFalse(close!!.toCompletableFuture().isDone)
            cleanup.complete(null)
            close!!.toCompletableFuture().join()
        } finally { cleanup.complete(null); composite.destroy().toCompletableFuture().join(); registration.close() }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val f = Fixture()
        try { block(f) } finally {
            f.service.close()
            f.world.physicsCoordinator.close()
            (f.world.tickManager as AutoCloseable).close()
        }
    }

    private class Fixture {
        val world = createTestWorld()
        val service = DefaultPartExtensionService()
        val calls = mutableListOf<String>()
        val descriptors = mutableListOf<DisplayVisualDescriptor>()
        val visuals = mutableListOf<Visual>()
        val dispatcher = QueuedDispatcher()
        val bundles = TrainPacketBundleCoordinator { _, _ -> }
        val bukkitWorld = proxy(World::class.java) { name, _ -> error("Unexpected world call $name") }
        val player = proxy(Player::class.java) { name, _ -> if (name == "getUniqueId") VIEWER else error(name) }
        val root = Visual("root", calls)
        val child = Child(world, calls)
        var pendingCleanup: CompletableFuture<Void>? = null
        var failCreateAt = -1
        var attempts = 0
        var onCreate: (() -> Unit)? = null
        val backend = object : DisplayRenderBackend {
            override fun create(world: World, descriptor: DisplayVisualDescriptor): DisplayVisual {
                attempts++
                if (attempts == failCreateAt) error("creation failed")
                descriptors += descriptor
                onCreate?.invoke()
                return Visual("text", calls, pendingCleanup).also(visuals::add)
            }
        }
        fun register(prepare: () -> PartExtensionFrame) = service.register(proxy(Plugin::class.java) { name, _ -> when (name) {
            "isEnabled" -> true; "getName" -> "StaticTest"; else -> null
        } }, object : PartExtensionRenderer {
            override val typeId = TYPE
            override fun validate(context: PartExtensionPackage) = Unit
            override fun prepare(context: PartExtensionRenderContext) = prepare.invoke()
        })
        fun frame() = PartExtensionFrame(listOf(PartExtensionText(Component.text("fixed"), ModelPose(2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0))))
        fun group() = ExtensionPartModel(world, ModelState(CompactTransform(position = Vector3d(3.0, 0.0, 0.0))),
            listOf(child), listOf(PartAnimationConfig("position", keyframes = listOf(
                PartAnimationKeyframeConfig(0.0, "base"), PartAnimationKeyframeConfig(1.0, "raised", offset = Vec3Config(y = 1.0)))))
                .toRuntimeAnimationMap(), TYPE,
            PartExtensionRenderContext("car-source", "pinned", PartExtensionPart("foreign/install", 1, JsonObject(emptyMap())), null),
            service, backend, dispatcher, bundles, 2f)
        fun composite(group: ExtensionPartModel) = CompositeModel(world, listOf(group), root, bukkitWorld)
        fun awaitApplied(composite: CompositeModel, x: Double = 0.0) = drive(composite, x) { descriptors.isNotEmpty() }
        fun awaitAttempted(composite: CompositeModel) = drive(composite, 0.0) { attempts > 0 }
        private fun drive(composite: CompositeModel, x: Double, done: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!done() && System.nanoTime() < deadline) { composite.updateRootPosition(Vector3d(x, 0.0, 0.0)); Thread.sleep(2) }
            assertTrue(done(), "worker frame was not consumed by owned root commit")
        }
    }

    private class Child(world: work.art1st.javarailways.core.api.physics.World, private val calls: MutableList<String>) :
        AbstractModel(world, ModelState(CompactTransform()), emptyList(), emptyMap()) {
        var destroyed = 0
        override fun collectDestruction(steps: DestructionSteps) { destroyed++ }
        override fun showVisualsTo(player: Player) { calls += "ordinary-show" }
        override fun hideVisualsFrom(player: Player) { calls += "ordinary-hide" }
    }

    private class Visual(val name: String, val calls: MutableList<String>, val pending: CompletableFuture<Void>? = null) : DisplayVisual {
        var destroys = 0
        var failMount = false
        var transformationValue: Transformation? = null
        override fun showTo(player: Player) { calls += "$name-show" }
        override fun hideFrom(player: Player) { calls += "$name-hide" }
        override fun teleport(location: Location) { calls += "$name-move" }
        override fun setTransformation(transformation: Transformation) { transformationValue = transformation; calls += "$name-transform" }
        override fun setItemStack(itemStack: ItemStack) = error("Not an item test")
        override fun setText(text: Component) = Unit
        override fun setBrightness(blockLight: Int, skyLight: Int) = Unit
        override fun clearBrightness() = Unit
        override fun addPassenger(passenger: DisplayVisual): Boolean { calls += "$name-mount"; return !failMount }
        override fun refreshPassengersFor(player: Player) { calls += "$name-passengers" }
        override fun destroy(): CompletionStage<Void> { destroys++; return pending ?: CompletableFuture.completedFuture(null) }
    }
    private class QueuedDispatcher : PlatformDispatcher by EntityCleanupDispatcher() {
        val actions = mutableListOf<() -> Unit>()
        override fun runForEntity(entity: Entity, action: () -> Unit, retired: () -> Unit): Boolean { actions += action; return true }
        fun drain() { val pending = actions.toList(); actions.clear(); pending.forEach { it() } }
    }
    private companion object {
        const val TYPE = "test:static"
        val VIEWER: UUID = UUID.randomUUID()
        fun <T> proxy(type: Class<T>, call: (String, Array<out Any?>?) -> Any?): T = type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args -> when (method.name) {
                "hashCode" -> System.identityHashCode(instance); "equals" -> instance === args?.get(0)
                "toString" -> type.simpleName; else -> call(method.name, args)
            } })
    }
}
