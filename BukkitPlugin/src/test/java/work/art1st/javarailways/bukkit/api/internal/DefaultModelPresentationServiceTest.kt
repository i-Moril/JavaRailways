package work.art1st.javarailways.bukkit.api.internal

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World as BukkitWorld
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.model.*
import work.art1st.javarailways.bukkit.render.backend.*
import work.art1st.javarailways.bukkit.render.chunk.RenderChunkManager
import work.art1st.javarailways.bukkit.render.chunk.ViewDistanceSettings
import work.art1st.javarailways.bukkit.render.model.ModelRuntimeTestSupport
import work.art1st.javarailways.bukkit.util.PlatformDispatcher
import work.art1st.javarailways.bukkit.util.PlatformDispatchMetrics
import work.art1st.javarailways.core.physics.train.createTestWorld
import java.lang.reflect.Proxy
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

class DefaultModelPresentationServiceTest {
    @Test
    fun `picking uses authored rotated geometry at the latest accepted pose without rendering`(): Unit = Fixture().use { f ->
        val box = ModelSelectionBox(0.0, 0.0, 0.0, 2.0, 1.0, 0.5)
        val handle = f.service.create(f.owner.plugin, f.worldId, "selected", ModelDefinition(definition().parts(), listOf(box)))
        val ray = ModelRay(10.25, 64.5, -5.0, 0.0, 0.0, 1.0, 10.0)
        assertTrue(f.service.pick(f.owner.plugin, f.worldId, ray).isEmpty)
        val half = kotlin.math.sqrt(0.5)
        handle.submit(ModelPresentation(1, ModelPose(10.0, 64.0, 0.0, 0.0, half, 0.0, half), mapOf("Lamp" to "Base")))
        val pick = f.service.pick(f.owner.plugin, f.worldId, ray).orElseThrow()
        assertEquals("selected", pick.objectId())
        assertEquals(3.0, pick.distance(), 1e-9)
        assertEquals(1L, pick.presentationRevision())
        assertTrue(f.backend.visuals.isEmpty(), "selection does not require rendering acknowledgment or run entity tasks")
        assertTrue(f.service.pick(f.owner.plugin, f.worldId, ModelRay(10.25, 64.5, -5.0, 0.0, 0.0, 1.0, 2.9)).isEmpty)
        assertTrue(f.service.pick(f.owner.plugin, f.worldId, ModelRay(12.0, 64.5, -5.0, 0.0, 0.0, 1.0, 10.0)).isEmpty)
        assertEquals(0.0, f.service.pick(f.owner.plugin, f.worldId,
            ModelRay(10.25, 64.5, -1.0, 0.0, 0.0, 1.0, 10.0)).orElseThrow().distance())
        handle.submit(state(2, x = 40.0))
        assertTrue(f.service.pick(f.owner.plugin, f.worldId, ray).isEmpty, "queued new pose replaces old selection immediately")
        handle.close()
    }

    @Test
    fun `picking isolates owners resolves equal hits and retires old world and handles`(): Unit = Fixture().use { f ->
        val selected = ModelDefinition(definition().parts(), listOf(ModelSelectionBox(-0.5, 0.0, -0.5, 0.5, 1.0, 0.5)))
        val ray = ModelRay(0.0, 64.5, -5.0, 0.0, 0.0, 1.0, 10.0)
        val other = Owner("Addon")
        f.service.create(other.plugin, f.worldId, "other", selected).submit(state(1))
        assertTrue(f.service.pick(f.owner.plugin, f.worldId, ray).isEmpty)
        f.create().submit(state(1)) // Model without authored boxes never gets an inferred box.
        val b = f.service.create(f.owner.plugin, f.worldId, "b", selected)
        val a = f.service.create(f.owner.plugin, f.worldId, "a", selected)
        b.submit(state(1)); a.submit(state(1))
        assertEquals("a", f.service.pick(f.owner.plugin, f.worldId, ray).orElseThrow().objectId())
        a.close()
        assertEquals("b", f.service.pick(f.owner.plugin, f.worldId, ray).orElseThrow().objectId())
        val replacement = f.service.create(f.owner.plugin, f.worldId, "a", selected)
        a.submit(state(99))
        assertEquals("b", f.service.pick(f.owner.plugin, f.worldId, ray).orElseThrow().objectId())
        replacement.submit(state(1))
        f.service.removeWorld(f.worldId, f.bukkitWorld)
        assertThrows(IllegalStateException::class.java) { f.service.pick(f.owner.plugin, f.worldId, ray) }
        f.service.registerWorld(f.worldId, bukkitWorld(f.worldId), f.railWorld, f.audiences)
        assertTrue(f.service.pick(f.owner.plugin, f.worldId, ray).isEmpty)
    }

    @Test
    fun `allocation and submit are value-only and both affinities display through the existing audience index`() {
        for (native in listOf(true, false)) Fixture(native).use { f ->
            val handle = f.create()
            assertTrue(f.backend.visuals.isEmpty())
            handle.submit(state(1))
            assertTrue(f.backend.visuals.isEmpty(), "submit must not inline entity creation even from an owner thread")
            f.dispatcher.drain()
            assertEquals(2, f.backend.visuals.size)
            assertEquals(listOf("item:show", "root:show"), f.backend.events.filter { it.endsWith(":show") })
            assertTrue(f.backend.visuals.all { it.viewers == setOf(f.player.uniqueId) })
            assertEquals(1, f.audiences.getRenderablesAround(f.player.location.toVector().toVector3d(), 64.0).size)
            val before = f.backend.events.toList()
            handle.submit(state(2))
            f.dispatcher.drain()
            assertEquals(before, f.backend.events, "revision-only change does no entity work")
            handle.submit(state(3, scene = "Green"))
            f.dispatcher.drain()
            assertEquals(Material.DIAMOND, f.backend.visuals.first().item.type)
            assertTrue(f.dispatcher.executedKinds.contains(if (native) "entity" else "location"))
        }
    }

    @Test
    fun `paused scheduled batches coalesce latest state and recheck the latest creation target`(): Unit = Fixture().use { f ->
        val handle = f.create()
        handle.submit(state(1, x = 0.0))
        f.dispatcher.runNext("async") // Captures the old region; do not run that region task yet.
        handle.submit(state(2, x = 80.0))
        handle.submit(state(3, x = 160.0, scene = "Green"))
        f.dispatcher.runNext("location")
        assertTrue(f.backend.visuals.isEmpty(), "newest pose is outside the captured owner and must reschedule")
        f.dispatcher.drain()
        assertEquals(2, f.backend.visuals.size, "superseded positions never create models")
        assertTrue(f.backend.visuals.all { it.location.x == 160.0 })
        assertEquals(Material.DIAMOND, f.backend.visuals.first().item.type)
    }

    @Test
    fun `invalid complete selections preserve pending state and old or closed revisions never revive it`(): Unit = Fixture().use { f ->
        val handle = f.create()
        assertThrows(IllegalArgumentException::class.java) { handle.submit(state(1, scene = "Missing")) }
        assertTrue(f.dispatcher.queue.isEmpty())
        handle.submit(state(2, scene = "Green"))
        assertThrows(IllegalArgumentException::class.java) {
            handle.submit(ModelPresentation(3, pose(20.0), mapOf("Lamp" to "Green", "Missing" to "Green")))
        }
        handle.submit(state(1, scene = "Missing"))
        f.dispatcher.drain()
        assertEquals(Material.DIAMOND, f.backend.visuals.first().item.type)
        handle.close()
        handle.close()
        handle.submit(state(100, scene = "Missing"))
        f.dispatcher.drain()
        assertTrue(f.backend.visuals.all { it.destroyCalls == 1 })
    }

    @Test
    fun `exact plugin instance and world instance own identities and closed duplicate can be replaced`(): Unit = Fixture().use { f ->
        val first = f.create()
        assertThrows(IllegalStateException::class.java) { f.create() }
        val other = Owner("Addon")
        val separate = f.service.create(other.plugin, f.worldId, "signal", definition())
        first.submit(state(1))
        f.dispatcher.drain()
        first.close()
        val replacement = f.create()
        replacement.submit(state(1, scene = "Green"))
        separate.close()
        f.dispatcher.drain()
        assertEquals(4, f.backend.visuals.size)
        assertTrue(f.backend.visuals.take(2).all { it.destroyCalls == 1 })
        assertTrue(f.backend.visuals.takeLast(2).all { it.destroyCalls == 0 })
    }

    @Test
    fun `closeOwner includes an individually closed handle with deletion still queued`(): Unit = Fixture().use { f ->
        val handle = f.create()
        handle.submit(state(1))
        f.dispatcher.drain()
        handle.close()
        val completion = f.service.closeOwner(f.owner.plugin).toCompletableFuture()
        assertFalse(completion.isDone)
        assertTrue(f.backend.visuals.all { it.destroyCalls == 0 })
        f.dispatcher.drain()
        completion.join()
        assertTrue(f.backend.visuals.all { it.destroyCalls == 1 && it.viewers.isEmpty() })
        assertTrue(f.service.closeOwner(f.owner.plugin).toCompletableFuture().isDone)
        f.service.create(f.owner.plugin, f.worldId, "signal", definition()).close()
    }

    @Test
    fun `owner disable listener closes actual handles while the enabled host finishes cleanup`(): Unit = Fixture().use { f ->
        val handle = f.create()
        handle.submit(state(1))
        f.dispatcher.drain()
        f.owner.enabled = false
        BukkitServiceTestSupport.withServer {
            ModelOwnerLifecycleListener(f.service, f.host.plugin).onPluginDisable(PluginDisableEvent(f.owner.plugin))
        }
        val completion = f.service.closeOwner(f.owner.plugin).toCompletableFuture()
        assertFalse(completion.isDone, "onDisable event return is not deletion completion")
        assertThrows(IllegalStateException::class.java) { f.create() }
        handle.submit(state(99))
        f.dispatcher.drain()
        completion.join()
        assertTrue(f.backend.visuals.all { it.destroyCalls == 1 })
    }

    @Test
    fun `world withdrawal and same UUID reload invalidate old handles without deleting the new binding`(): Unit = Fixture().use { f ->
        val old = f.create()
        old.submit(state(1))
        f.dispatcher.drain()
        f.service.removeWorld(f.worldId, f.bukkitWorld)
        assertThrows(IllegalStateException::class.java) { f.create() }
        val reloaded = bukkitWorld(f.worldId)
        f.service.registerWorld(f.worldId, reloaded, f.railWorld, f.audiences)
        f.service.removeWorld(f.worldId, f.bukkitWorld) // Late cleanup of the old instance.
        old.submit(state(100, scene = "Green"))
        val current = f.create()
        current.submit(state(1))
        f.dispatcher.drain()
        assertEquals(4, f.backend.visuals.size)
        assertTrue(f.backend.visuals.take(2).all { it.destroyCalls == 1 })
        assertTrue(f.backend.visuals.takeLast(2).all { it.location.world === reloaded })
    }

    @Test
    fun `relocation destroys on old entity owner before rebuilding on the new location owner`(): Unit = Fixture().use { f ->
        val handle = f.create()
        handle.submit(state(1))
        f.dispatcher.drain()
        handle.submit(state(2, x = 160.0, scene = "Green"))
        f.dispatcher.runNext("async")
        f.dispatcher.runNext("entity")
        assertTrue(f.backend.visuals.all { it.destroyCalls == 1 })
        assertEquals(2, f.backend.visuals.size, "creation has not run on the destination owner yet")
        f.dispatcher.drain()
        assertEquals(4, f.backend.visuals.size)
        assertTrue(f.backend.visuals.takeLast(2).all { it.location.x == 160.0 })
        assertTrue(f.backend.events.indexOf("item:destroy") < f.backend.events.lastIndexOf("item:create"))
    }

    @Test
    fun `retired root uses static original location for remaining partial resources`(): Unit = Fixture().use { f ->
        val handle = f.create()
        handle.submit(state(1))
        f.dispatcher.drain()
        f.dispatcher.retired += f.backend.visuals.last().entity
        handle.close()
        f.dispatcher.drain()
        f.service.closeOwner(f.owner.plugin).toCompletableFuture().join()
        assertTrue(f.backend.visuals.all { it.destroyCalls == 1 })
    }

    @Test
    fun `render creation failure cleans partial objects and does not fail owner cleanup`(): Unit = Fixture().use { f ->
        f.backend.failCreateAt = 2
        f.create().submit(state(1))
        f.dispatcher.drain()
        assertEquals(1, f.backend.visuals.size)
        assertEquals(1, f.backend.visuals.single().destroyCalls)
        f.service.closeOwner(f.owner.plugin).toCompletableFuture().join()
        assertTrue(f.logs.any { it.message.contains("Addon") && it.message.contains("signal") })
        assertTrue(f.audiences.getRenderablesAround(f.player.location.toVector().toVector3d(), 64.0).isEmpty())
    }

    @Test
    fun `failed deletion stays observable by later owner cleanup and still tries every visual`(): Unit = Fixture().use { f ->
        val handle = f.create()
        handle.submit(state(1))
        f.dispatcher.drain()
        f.backend.visuals.last().failDestroy = true
        handle.close()
        f.dispatcher.drain()
        assertTrue(f.backend.visuals.all { it.destroyCalls == 1 }, "a failed root deletion cannot skip children")
        val first = f.service.closeOwner(f.owner.plugin).toCompletableFuture()
        assertThrows(CompletionException::class.java) { first.join() }
        assertThrows(CompletionException::class.java) { f.service.closeOwner(f.owner.plugin).toCompletableFuture().join() }
    }

    @Test
    fun `audience scheduling failure removes the already indexed adapter and cleans all visuals`(): Unit = Fixture().use { f ->
        f.dispatcher.rejectPlayerDrain = true
        f.create().submit(state(1))
        f.dispatcher.drain()
        assertTrue(f.backend.visuals.all { it.destroyCalls == 1 })
        assertTrue(f.audiences.getRenderablesAround(f.player.location.toVector().toVector3d(), 64.0).isEmpty())
        f.service.closeOwner(f.owner.plugin).toCompletableFuture().join()
        assertTrue(f.logs.any { it.thrown?.message == "scripted audience scheduler failure" })
    }

    @Test
    fun `audience drain captured before withdrawal cannot leave visibility entries after removal`(): Unit = Fixture().use { f ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val once = java.util.concurrent.atomic.AtomicBoolean()
        val executor = Executors.newSingleThreadExecutor()
        val handle = f.create()
        try {
            handle.submit(state(1))
            f.dispatcher.runNext("async")
            f.dispatcher.runNext("location")
            // The player ID is first read after the pending update was taken, before visible registration.
            f.beforePlayerIdRead = { if (once.compareAndSet(false, true)) {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            } }
            val viewer = executor.submit { f.dispatcher.runNext("entity") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            handle.close()
            f.dispatcher.runNext("async")
            f.dispatcher.runNext("entity")
            assertTrue(f.backend.visuals.all { it.destroyCalls == 1 })
            release.countDown()
            viewer.get(5, TimeUnit.SECONDS)
            f.beforePlayerIdRead = null
            f.dispatcher.drain()
            assertEquals(0, f.audiences.audienceMetrics().visibleEntries)
            assertTrue(f.backend.visuals.all { it.viewers.isEmpty() })
            f.service.closeOwner(f.owner.plugin).toCompletableFuture().join()
        } finally { release.countDown(); f.beforePlayerIdRead = null; executor.shutdownNow() }
    }

    @Test
    fun `disabled host diagnoses unfinished deletion rather than claiming scheduler completion`(): Unit = Fixture().use { f ->
        val handle = f.create()
        handle.submit(state(1))
        f.dispatcher.drain()
        handle.close()
        val completion = f.service.closeOwner(f.owner.plugin).toCompletableFuture()
        f.host.enabled = false
        f.service.close()
        assertThrows(CompletionException::class.java) { completion.join() }
        assertThrows(CompletionException::class.java) { f.service.closeOwner(f.owner.plugin).toCompletableFuture().join() }
        assertTrue(f.backend.visuals.all { it.destroyCalls == 0 }, "disabled scheduler has not removed entities")
        assertTrue(f.logs.any { it.thrown?.message?.contains("before model cleanup completed") == true })
    }

    @Test
    fun `blocked backend creation cannot block value submission or close`(): Unit = Fixture().use { f ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val handle = f.create()
        try {
            f.backend.beforeCreate = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            handle.submit(state(1))
            f.dispatcher.runNext("async")
            val render = executor.submit { f.dispatcher.runNext("location") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val submit = executor.submit { handle.submit(state(2, scene = "Green")); handle.close() }
            submit.get(5, TimeUnit.SECONDS)
            val cleanup = f.service.closeOwner(f.owner.plugin).toCompletableFuture()
            assertFalse(cleanup.isDone)
            release.countDown()
            render.get(5, TimeUnit.SECONDS)
            f.dispatcher.drain()
            cleanup.join()
            assertTrue(f.backend.visuals.all { it.destroyCalls == 1 && it.viewers.isEmpty() })
        } finally { release.countDown(); executor.shutdownNow() }
    }

    private class Fixture(native: Boolean = true) : AutoCloseable {
        val worldId = UUID.randomUUID()
        val bukkitWorld = bukkitWorld(worldId)
        val railWorld = createTestWorld()
        val host = Owner("JavaRailways")
        val owner = Owner("Addon")
        val dispatcher = ControlledDispatcher()
        val logs = mutableListOf<LogRecord>()
        val logger = Logger.getLogger("model-service-${UUID.randomUUID()}").apply {
            useParentHandlers = false
            addHandler(object : Handler() {
                override fun publish(record: LogRecord) { logs += record }
                override fun flush() = Unit
                override fun close() = Unit
            })
        }
        @Volatile var beforePlayerIdRead: (() -> Unit)? = null
        val player = player(bukkitWorld) { beforePlayerIdRead?.invoke() }
        val audiences = RenderChunkManager(
            bukkitWorld, dispatcher = dispatcher, playerLookup = { if (it == player.uniqueId) player else null },
            settingsFor = { ViewDistanceSettings(64.0, 16.0) }, registerListener = {}, initialPlayers = listOf(player)
        )
        val backend = RecordingBackend(dispatcher, native)
        val service = DefaultModelPresentationService(host.plugin, dispatcher, backend, 1.0f, logger)
        init { dispatcher.entityLocations[player] = player.location; dispatcher.drain(); service.registerWorld(worldId, bukkitWorld, railWorld, audiences) }
        fun create() = service.create(owner.plugin, worldId, "signal", definition())
        override fun close() {
            host.enabled = true
            service.close()
            dispatcher.drain()
            audiences.close()
            dispatcher.drain()
            railWorld.physicsCoordinator.close()
            (railWorld.tickManager as AutoCloseable).close()
        }
    }

    private class Owner(val name: String) {
        @Volatile var enabled = true
        val plugin: Plugin = proxy(Plugin::class.java) { method, _ -> when (method) {
            "getName", "toString" -> name
            "isEnabled" -> enabled
            else -> null
        } }
    }

    private class ControlledDispatcher : PlatformDispatcher {
        data class Task(val kind: String, val location: Location?, val entity: Entity?, val action: () -> Unit)
        val queue = ConcurrentLinkedQueue<Task>()
        val entityLocations = IdentityHashMap<Entity, Location>()
        val retired = java.util.Collections.newSetFromMap(IdentityHashMap<Entity, Boolean>())
        val executedKinds = mutableListOf<String>()
        var rejectPlayerDrain = false
        private val current = ThreadLocal<Task?>()
        override fun runAsync(action: () -> Unit) { queue += Task("async", null, null, action) }
        override fun runAt(location: Location, action: () -> Unit) {
            if (owns(location)) action() else queue += Task("location", location.clone(), null, action)
        }
        override fun runForEntity(entity: Entity, action: () -> Unit, retired: () -> Unit): Boolean {
            if (entity in this.retired) return false // Real scheduler false means neither callback was accepted.
            if (owns(entity)) action() else queue += Task("entity", entityLocations[entity] ?: entity.location, entity, action)
            return true
        }
        override fun runForEntityLater(entity: Entity, delayTicks: Long, action: () -> Unit, retired: () -> Unit): ScheduledTask? {
            check(!rejectPlayerDrain) { "scripted audience scheduler failure" }
            queue += Task("entity", entityLocations.getValue(entity), entity, action)
            return proxy(ScheduledTask::class.java) { _, _ -> null }
        }
        override fun owns(location: Location): Boolean {
            val owned = current.get()?.location ?: return false
            return owned.world === location.world && kotlin.math.floor(owned.x / 16) == kotlin.math.floor(location.x / 16)
        }
        override fun owns(entity: Entity): Boolean = current.get()?.entity === entity || entityLocations[entity]?.let(::owns) == true
        fun runNext(kind: String) {
            val task = queue.firstOrNull { it.kind == kind } ?: error("No queued $kind task")
            check(queue.remove(task))
            current.set(task)
            try { executedKinds += task.kind; task.action() } finally { current.remove() }
        }
        fun drain() { var count = 0; while (queue.isNotEmpty()) { check(count++ < 100); runNext(queue.peek().kind) } }
        override fun runGlobal(action: () -> Unit) = error("No global scheduling")
        override fun runGlobalAtFixedRate(initialDelayTicks: Long, periodTicks: Long, action: () -> Unit): ScheduledTask = error("No ticker")
        override fun runForEntityAtFixedRate(entity: Entity, initialDelayTicks: Long, periodTicks: Long, action: (ScheduledTask) -> Unit, retired: () -> Unit): ScheduledTask? = error("No ticker")
        override fun metrics() = PlatformDispatchMetrics(0, 0, 0, 0, 0, 0, 0, 0)
    }

    private class RecordingBackend(val dispatcher: ControlledDispatcher, val native: Boolean) : DisplayRenderBackend {
        val visuals = mutableListOf<Visual>()
        val events = mutableListOf<String>()
        var attempts = 0
        var failCreateAt: Int? = null
        var beforeCreate: (() -> Unit)? = null
        override fun create(world: BukkitWorld, descriptor: DisplayVisualDescriptor): DisplayVisual {
            assertTrue(dispatcher.owns(descriptor.location), "creation belongs to the exact target location")
            beforeCreate?.invoke()
            check(++attempts != failCreateAt) { "scripted create failure" }
            val content = (descriptor.content as DisplayVisualContent.Item).itemStack
            val name = if (content.type.isAir) "root" else "item"
            return Visual(name, descriptor.location.clone(), content.clone()).also {
                visuals += it
                dispatcher.entityLocations[it.entity] = descriptor.location.clone()
                events += "$name:create"
            }
        }
        inner class Visual(val name: String, val location: Location, var item: ItemStack) : DisplayVisual {
            val entity = proxy(Entity::class.java) { _, _ -> null }
            override val affinityEntity: Entity? get() = if (native) entity else null
            val viewers = mutableSetOf<UUID>()
            var destroyCalls = 0
            var failDestroy = false
            private fun owner() = assertTrue(dispatcher.owns(location), "update/delete runs on the model's current owner")
            override fun showTo(player: Player) { assertTrue(dispatcher.owns(player)); viewers += player.uniqueId; events += "$name:show" }
            override fun hideFrom(player: Player) { assertTrue(dispatcher.owns(player)); viewers -= player.uniqueId; events += "$name:hide" }
            override fun teleport(location: Location) { owner(); assertEquals(this.location.x, location.x); events += "$name:position" }
            override fun setTransformation(transformation: Transformation) { owner(); events += "$name:transform" }
            override fun setItemStack(itemStack: ItemStack) { owner(); item = itemStack.clone(); events += "$name:item" }
            override fun setBrightness(blockLight: Int, skyLight: Int) { owner(); events += "$name:brightness" }
            override fun clearBrightness() { owner(); events += "$name:brightness-clear" }
            override fun addPassenger(passenger: DisplayVisual): Boolean { owner(); return true }
            override fun destroy() { owner(); destroyCalls++; events += "$name:destroy"; check(!failDestroy) { "scripted delete failure" }; viewers.clear() }
        }
    }

    companion object {
        @JvmStatic @BeforeAll fun bootstrap() = ModelRuntimeTestSupport.bootstrap()
        private fun pose(x: Double = 0.0) = ModelPose(x, 64.0, 0.0, 0.0, 0.0, 0.0, 1.0)
        private fun state(revision: Long, x: Double = 0.0, scene: String = "Base") = ModelPresentation(revision, pose(x), mapOf("Lamp" to scene))
        private fun definition(): ModelDefinition {
            val animation = ModelAnimation("Lamp", listOf(
                ModelAnimation.Frame(0.0, "Base", ModelPose.IDENTITY, ModelScale.UNIT, null),
                ModelAnimation.Frame(1.0, "Green", ModelPose.IDENTITY, ModelScale.UNIT, ItemAppearance(Material.DIAMOND, null, null, null))
            ))
            return ModelDefinition(listOf(ModelPart.Item(ModelPose.IDENTITY, ModelScale.UNIT,
                ItemAppearance(Material.PAPER, null, null, null), listOf(animation))))
        }
        private fun bukkitWorld(id: UUID): BukkitWorld = proxy(BukkitWorld::class.java) { method, _ -> when (method) {
            "getUID" -> id
            "getName", "toString" -> "ModelWorld"
            else -> null
        } }
        private fun player(world: BukkitWorld, beforeIdRead: () -> Unit): Player {
            val id = UUID.randomUUID()
            return proxy(Player::class.java) { method, _ -> when (method) {
                "getUniqueId" -> { beforeIdRead(); id }
                "getWorld" -> world
                "getLocation" -> Location(world, 0.0, 64.0, 0.0)
                else -> null
            } }
        }
        private fun <T> proxy(type: Class<T>, handler: (String, Array<out Any>?) -> Any?): T {
            val proxy = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args -> when (method.name) {
                "equals" -> instance === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(instance)
                else -> handler(method.name, args)
            } }
            return type.cast(proxy)
        }
    }
}
