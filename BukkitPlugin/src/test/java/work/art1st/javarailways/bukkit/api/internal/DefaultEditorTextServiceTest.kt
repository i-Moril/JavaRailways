package work.art1st.javarailways.bukkit.api.internal

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.Color
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.editor.EditorTextLabel
import work.art1st.javarailways.bukkit.api.editor.EditorMarkerGlyph
import work.art1st.javarailways.bukkit.api.editor.EditorMarkerLabels
import net.kyori.adventure.text.format.NamedTextColor
import org.joml.Vector3d
import work.art1st.javarailways.bukkit.render.model.ModelRuntimeTestSupport
import work.art1st.javarailways.bukkit.util.PlatformDispatcher
import work.art1st.javarailways.bukkit.util.PlatformDispatchMetrics
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

class DefaultEditorTextServiceTest {
    @Test
    fun `host cleanup observes earlier queued reconciliation before approving unload`() = Fixture().use { f ->
        val handle = f.create()
        handle.submit(0, listOf(label("a")))
        f.dispatcher.drain()
        assertEquals(1, f.visuals.size)
        handle.submit(1, listOf(label("a", text = "queued update")))
        val completion = f.service.beginHostCleanup().toCompletableFuture()
        assertFalse(completion.isDone)
        f.dispatcher.drain()
        completion.join()
        assertTrue(f.visuals.all { it.removals == 1 })
    }

    @Test
    fun `fixed glyph appearance updates on the entity owner and readable style restores completely`() = Fixture().use { f ->
        val handle = f.create()
        val glyph = EditorMarkerLabels.at("a", EditorMarkerGlyph.DIRECTION, Vector3d(0.0, 64.0, 0.0),
            Vector3d(0.0, 0.0, 1.0), Vector3d(0.0, 1.0, 0.0), 1.0, 2.0, NamedTextColor.AQUA)
        handle.submit(0, listOf(glyph))
        f.dispatcher.drain()
        val shown = f.visuals.single()
        assertEquals(Display.Billboard.FIXED, shown.billboard)
        assertFalse(shown.shadowed)
        assertEquals(0, shown.background!!.alpha)
        assertEquals(Display.Brightness(15, 15), shown.brightness)
        assertEquals(5f, shown.transform!!.scale.x)
        assertEquals(10f, shown.transform!!.scale.y)
        val operations = f.events.toList()
        handle.submit(1, listOf(glyph))
        f.dispatcher.drain()
        assertEquals(operations, f.events)
        // Same world anchor: change style in place, rather than spawning another display.
        handle.submit(2, listOf(EditorTextLabel("a", glyph.x(), glyph.y(), glyph.z(), Component.text("caption"))))
        f.dispatcher.drain()
        assertEquals(1, f.visuals.size)
        assertEquals(Display.Billboard.CENTER, shown.billboard)
        assertTrue(shown.shadowed)
        assertNull(shown.background)
        assertNull(shown.brightness)
        assertEquals(1f, shown.transform!!.scale.x)
        assertEquals(0f, shown.transform!!.translation.length())
    }

    @Test
    fun `stable complete lists create update delete and no-op only the intended viewer`() = Fixture().use { f ->
        val handle = f.create()
        assertTrue(f.visuals.isEmpty())
        val input = mutableListOf(label("a"), label("b", 80.0))
        handle.submit(0, input)
        input.clear()
        assertTrue(f.visuals.isEmpty(), "submit never spawns inline")
        f.dispatcher.drain()
        val a = f.visuals[0]
        val b = f.visuals[1]
        assertTrue(f.visuals.all { !it.visibleByDefault && !it.persistent && it.billboard == Display.Billboard.CENTER })
        assertTrue(f.visuals.all { it.viewers == setOf(f.viewer) })
        val before = f.events.toList()
        handle.submit(1, listOf(label("a"), label("b", 80.0)))
        f.dispatcher.drain()
        assertEquals(before, f.events, "revision-only change performs no display operations")
        handle.submit(2, listOf(label("a", text = "Changed"), label("b", 80.0)))
        f.dispatcher.drain()
        assertEquals(2, f.visuals.size)
        assertEquals(Component.text("Changed"), a.text)
        assertEquals(1, b.textWrites, "unchanged B is untouched")
        handle.submit(3, listOf(label("a", 160.0, "Moved")))
        f.dispatcher.drain()
        assertEquals(1, a.removals)
        assertEquals(1, b.removals)
        assertEquals(160.0, f.visuals.last().location.x)
        handle.submit(4, emptyList())
        f.dispatcher.drain()
        assertTrue(f.visuals.all { it.removals == 1 && it.viewers.isEmpty() })
    }

    @Test
    fun `one pending list validates before replacement and ignores stale or closed work`() = Fixture().use { f ->
        val handle = f.create()
        handle.submit(1, listOf(label("a")))
        assertThrows(IllegalArgumentException::class.java) { handle.submit(2, listOf(label("a"), label("a"))) }
        assertThrows(IllegalArgumentException::class.java) { handle.submit(-1, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { label("bad", Double.NaN) }
        handle.submit(2, listOf(label("b", 80.0)))
        handle.submit(3, listOf(label("c", 160.0)))
        handle.submit(1, listOf(label("old")))
        assertEquals(1, f.dispatcher.queue.size, "only one asynchronous reconciliation is scheduled")
        f.dispatcher.drain()
        assertEquals(1, f.visuals.size)
        assertEquals(160.0, f.visuals.single().location.x)
        handle.close()
        handle.submit(100, listOf(label("late")))
        f.dispatcher.drain()
        assertEquals(1, f.visuals.size)
        assertEquals(1, f.visuals.single().removals)
    }

    @Test
    fun `close at queued creation or viewer show cannot revive display`() {
        for (stage in listOf("before-location", "before-show")) Fixture().use { f ->
            val handle = f.create()
            handle.submit(0, listOf(label("a")))
            f.dispatcher.runNext("async")
            f.dispatcher.runNext("viewer")
            if (stage == "before-show") f.dispatcher.runNext("location")
            handle.close()
            val cleanup = f.service.closeOwner(f.owner.plugin).toCompletableFuture()
            assertFalse(cleanup.isDone)
            f.dispatcher.drain()
            cleanup.join()
            assertTrue(f.visuals.all { it.removals == 1 && it.viewers.isEmpty() })
            assertFalse(f.events.any { it == "show" })
        }
    }

    @Test
    fun `close and submission do not block while actual spawn or show is in progress`() {
        for (stage in listOf("spawn", "show")) Fixture().use { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val handle = f.create()
            try {
                val pause = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                if (stage == "spawn") f.beforeSpawn = pause else f.beforeShow = pause
                handle.submit(0, listOf(label("a")))
                f.dispatcher.runNext("async")
                f.dispatcher.runNext("viewer")
                if (stage == "show") f.dispatcher.runNext("location")
                val display = executor.submit { f.dispatcher.runNext(if (stage == "spawn") "location" else "viewer") }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                handle.submit(1, listOf(label("late")))
                handle.close()
                val cleanup = f.service.closeOwner(f.owner.plugin).toCompletableFuture()
                assertFalse(cleanup.isDone)
                release.countDown()
                display.get(5, TimeUnit.SECONDS)
                f.dispatcher.drain()
                cleanup.join()
                assertEquals(1, f.visuals.single().removals)
                assertTrue(f.visuals.single().viewers.isEmpty())
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }

    @Test
    fun `player world owner and host lifecycle close exact handles with host-owned cleanup`() {
        for (event in listOf("quit", "world-change", "world-unload", "owner-disable")) Fixture().use { f ->
            val handle = f.create()
            handle.submit(0, listOf(label("a")))
            f.dispatcher.drain()
            when (event) {
                "quit" -> f.listener.onQuit(PlayerQuitEvent(f.viewer, Component.empty()))
                "world-change" -> {
                    val old = f.world
                    f.world = f.world(UUID.randomUUID())
                    f.listener.onChangedWorld(PlayerChangedWorldEvent(f.viewer, old))
                }
                "world-unload" -> f.listener.onWorldUnload(WorldUnloadEvent(f.world))
                else -> {
                    f.owner.enabled = false
                    BukkitServiceTestSupport.withServer {
                        f.listener.onPluginDisable(PluginDisableEvent(f.owner.plugin))
                    }
                }
            }
            handle.submit(10, listOf(label("late")))
            val cleanup = f.service.closeOwner(f.owner.plugin).toCompletableFuture()
            assertFalse(cleanup.isDone)
            f.dispatcher.drain()
            cleanup.join()
            assertEquals(1, f.visuals.single().removals)
            assertTrue(f.host.enabled, "disabled add-on does not own cleanup scheduling")
        }
    }

    @Test
    fun `identities are actual plugin player and world instances and duplicate can be replaced`() = Fixture().use { f ->
        val first = f.create()
        assertThrows(IllegalStateException::class.java) { f.create() }
        f.ownerContext { f.service.create(Owner("Addon").plugin, f.viewer, "object").close() }
        first.submit(0, listOf(label("old")))
        f.dispatcher.drain()
        val oldWorld = f.world
        f.listener.onWorldUnload(WorldUnloadEvent(oldWorld))
        f.world = f.world(oldWorld.uid)
        f.loadedWorld = f.world
        val replacement = f.create()
        replacement.submit(0, listOf(label("new")))
        f.dispatcher.drain()
        assertEquals(1, f.visuals.first().removals)
        assertEquals(0, f.visuals.last().removals)
        assertSame(f.world, f.visuals.last().location.world)
        assertNotSame(oldWorld, f.world)
        replacement.close()
        f.create().close()
    }

    @Test
    fun `invalid lifecycle and off-owner creation reject before allocating`() = Fixture().use { f ->
        assertThrows(IllegalStateException::class.java) { f.service.create(f.owner.plugin, f.viewer, "object") }
        f.online = false
        assertThrows(IllegalStateException::class.java) { f.create() }
        f.online = true
        f.loadedWorld = f.world(UUID.randomUUID())
        assertThrows(IllegalStateException::class.java) { f.create() }
        f.loadedWorld = f.world
        f.owner.enabled = false
        assertThrows(IllegalStateException::class.java) { f.create() }
        f.owner.enabled = true
        f.host.enabled = false
        assertThrows(IllegalStateException::class.java) { f.create() }
    }

    @Test
    fun `removal failures stay observable and do not prevent other labels cleanup`() = Fixture().use { f ->
        val handle = f.create()
        handle.submit(0, listOf(label("a"), label("b", 80.0)))
        f.dispatcher.drain()
        f.visuals.first().failRemove = true
        handle.close()
        val cleanup = f.service.closeOwner(f.owner.plugin).toCompletableFuture()
        f.dispatcher.drain()
        assertThrows(CompletionException::class.java) { cleanup.join() }
        assertEquals(1, f.visuals.last().removals)
        assertThrows(CompletionException::class.java) { f.service.closeOwner(f.owner.plugin).toCompletableFuture().join() }
        assertTrue(f.logs.any { it.message.contains("object object") && it.thrown != null })
    }

    @Test
    fun `host shutdown and scheduler rejection cannot claim cleanup succeeded`() {
        for (failure in listOf("host", "async", "entity")) Fixture().use { f ->
            val handle = f.create()
            handle.submit(0, listOf(label("a")))
            f.dispatcher.drain()
            when (failure) {
                "host" -> { handle.close(); f.host.enabled = false; f.service.close() }
                "async" -> { f.dispatcher.rejectAsync = true; handle.close() }
                else -> { f.dispatcher.rejectEntity = true; handle.close() }
            }
            val cleanup = f.service.closeOwner(f.owner.plugin).toCompletableFuture()
            f.dispatcher.drain()
            assertThrows(CompletionException::class.java) { cleanup.join() }
            assertThrows(CompletionException::class.java) { f.service.closeOwner(f.owner.plugin).toCompletableFuture().join() }
        }
    }

    @Test
    fun `retired display falls back to location owner and retired viewer cleans generated entity`() {
        Fixture().use { f ->
            val handle = f.create()
            handle.submit(0, listOf(label("a")))
            f.dispatcher.drain()
            f.dispatcher.retired += f.visuals.single().entity
            handle.close()
            val cleanup = f.service.closeOwner(f.owner.plugin).toCompletableFuture()
            f.dispatcher.drain()
            cleanup.join()
            assertEquals(1, f.visuals.single().removals)
        }
        Fixture().use { f ->
            val handle = f.create()
            handle.submit(0, listOf(label("a")))
            f.dispatcher.runNext("async")
            f.dispatcher.runNext("viewer")
            f.dispatcher.runNext("location")
            f.dispatcher.retired += f.viewer
            f.dispatcher.drain()
            f.service.closeOwner(f.owner.plugin).toCompletableFuture().join()
            assertTrue(f.visuals.single().viewers.isEmpty())
            assertEquals(1, f.visuals.single().removals)
        }
    }

    @Test
    fun `spawn initialization and viewer show failures clean the allocated entity`() {
        for (failure in listOf("initialize", "show")) Fixture().use { f ->
            f.failInitialize = failure == "initialize"
            f.failShow = failure == "show"
            f.create().submit(0, listOf(label("a")))
            f.dispatcher.drain()
            f.service.closeOwner(f.owner.plugin).toCompletableFuture().join()
            assertEquals(1, f.visuals.single().removals)
            assertTrue(f.visuals.single().viewers.isEmpty())
            assertTrue(f.logs.any { it.thrown != null })
        }
    }

    private class Fixture : AutoCloseable {
        val dispatcher = ControlledDispatcher()
        val visuals = mutableListOf<Visual>()
        val events = mutableListOf<String>()
        var world = world(UUID.randomUUID())
        var loadedWorld = world
        var online = true
        var failShow = false
        var failInitialize = false
        var beforeSpawn: (() -> Unit)? = null
        var beforeShow: (() -> Unit)? = null
        val server = proxy(Server::class.java) { method, _ -> when (method) {
            "getWorld" -> loadedWorld
            else -> null
        } }
        val host = Owner("JavaRailways") { server }
        val owner = Owner("Addon")
        val viewer: Player = proxy(Player::class.java) { method, args ->
            when (method) {
                "getWorld", "isOnline" -> {
                    assertTrue(dispatcher.ownsViewer, "viewer reads belong to viewer owner")
                    if (method == "getWorld") world else online
                }
                "getLocation" -> Location(world, 0.0, 64.0, 0.0)
                "showEntity" -> {
                    assertTrue(dispatcher.ownsViewer)
                    assertSame(host.plugin, args!![0])
                    beforeShow?.invoke()
                    check(!failShow) { "scripted show failure" }
                    visuals.single { it.entity === args[1] }.viewers += viewerInstance()
                    events += "show"
                    null
                }
                else -> null
            }
        }
        private fun viewerInstance() = viewer
        val logs = mutableListOf<LogRecord>()
        private val logger = Logger.getLogger("editor-text-${UUID.randomUUID()}").apply {
            useParentHandlers = false
            addHandler(object : Handler() {
                override fun publish(record: LogRecord) { logs += record }
                override fun flush() = Unit
                override fun close() = Unit
            })
        }
        val service = DefaultEditorTextService(host.plugin, dispatcher, logger)
        val listener = EditorTextLifecycleListener(service, host.plugin)
        init { dispatcher.viewer = viewer }
        fun create() = ownerContext { service.create(owner.plugin, viewer, "object") }
        fun <T> ownerContext(action: () -> T): T = dispatcher.asViewer(action)
        fun world(id: UUID): World = proxy(World::class.java) { method, args -> when (method) {
            "getUID" -> id
            "getName" -> "EditorWorld"
            "spawn" -> {
                val target = args!![0] as Location
                assertTrue(dispatcher.owns(target), "spawn belongs to label location")
                beforeSpawn?.invoke()
                val visual = Visual(target.clone())
                visuals += visual
                @Suppress("UNCHECKED_CAST")
                (args.last() as Consumer<TextDisplay>).accept(visual.entity)
                visual.entity
            }
            else -> null
        } }

        inner class Visual(val location: Location) {
            var text: Component? = null
            var textWrites = 0
            var visibleByDefault = true
            var persistent = true
            var billboard: Display.Billboard? = null
            var shadowed = false
            var background: Color? = null
            var brightness: Display.Brightness? = null
            var transform: Transformation? = null
            var removals = 0
            var failRemove = false
            val viewers = Collections.newSetFromMap(IdentityHashMap<Player, Boolean>())
            val entity: TextDisplay = proxy(TextDisplay::class.java) { method, args ->
                assertTrue(dispatcher.owns(location) || dispatcher.currentEntity() === entityInstance(), "display mutation belongs to entity/location owner")
                when (method) {
                    "text" -> { check(!failInitialize) { "scripted text failure" }; text = args!![0] as Component; textWrites++; events += "text"; null }
                    "setVisibleByDefault" -> { visibleByDefault = args!![0] as Boolean; null }
                    "setPersistent" -> { persistent = args!![0] as Boolean; null }
                    "setBillboard" -> { billboard = args!![0] as Display.Billboard; null }
                    "setShadowed" -> { shadowed = args!![0] as Boolean; null }
                    "setBackgroundColor" -> { background = args!![0] as Color?; null }
                    "setBrightness" -> { brightness = args!![0] as Display.Brightness?; null }
                    "setTransformation" -> { transform = args!![0] as Transformation; events += "appearance"; null }
                    "remove" -> { check(!failRemove) { "scripted delete failure" }; removals++; viewers.clear(); events += "remove"; null }
                    else -> null
                }
            }
            private fun entityInstance() = entity
        }

        override fun close() {
            host.enabled = true
            service.close()
            dispatcher.rejectAsync = false
            dispatcher.rejectEntity = false
            dispatcher.drain()
        }
    }

    private class ControlledDispatcher : PlatformDispatcher {
        data class Task(val kind: String, val location: Location?, val entity: Entity?, val action: () -> Unit, val retired: () -> Unit = {})
        val queue = ConcurrentLinkedQueue<Task>()
        val retired = Collections.newSetFromMap(IdentityHashMap<Entity, Boolean>())
        lateinit var viewer: Player
        var rejectAsync = false
        var rejectEntity = false
        private val current = ThreadLocal<Task?>()
        val ownsViewer get() = current.get()?.kind == "viewer"
        fun currentEntity() = current.get()?.entity
        fun <T> asViewer(action: () -> T): T {
            current.set(Task("viewer", null, viewer, {}))
            try { return action() } finally { current.remove() }
        }
        override fun runAsync(action: () -> Unit) { check(!rejectAsync) { "scripted async rejection" }; queue += Task("async", null, null, action) }
        override fun runAt(location: Location, action: () -> Unit) {
            if (owns(location)) action() else queue += Task("location", location.clone(), null, action)
        }
        override fun runForEntity(entity: Entity, action: () -> Unit, retired: () -> Unit): Boolean {
            check(!rejectEntity) { "scripted entity rejection" }
            if (entity in this.retired) return false
            if (owns(entity)) action() else queue += Task(if (entity === viewer) "viewer" else "entity", null, entity, action, retired)
            return true
        }
        override fun owns(location: Location): Boolean {
            val target = current.get()?.location ?: return false
            return location.world === target.world && kotlin.math.floor(target.x / 16) == kotlin.math.floor(location.x / 16)
        }
        override fun owns(entity: Entity) = current.get()?.entity === entity
        fun runNext(kind: String) {
            val task = queue.firstOrNull { it.kind == kind } ?: error("No $kind task")
            check(queue.remove(task))
            current.set(task)
            try { if (task.entity in retired) task.retired() else task.action() } finally { current.remove() }
        }
        fun drain() { var count = 0; while (queue.isNotEmpty()) { check(count++ < 100); runNext(queue.peek().kind) } }
        override fun runGlobal(action: () -> Unit) = error("No global scheduler")
        override fun runGlobalAtFixedRate(initialDelayTicks: Long, periodTicks: Long, action: () -> Unit): ScheduledTask = error("No ticker")
        override fun runForEntityLater(entity: Entity, delayTicks: Long, action: () -> Unit, retired: () -> Unit): ScheduledTask? = error("No delayed work")
        override fun runForEntityAtFixedRate(entity: Entity, initialDelayTicks: Long, periodTicks: Long, action: (ScheduledTask) -> Unit, retired: () -> Unit): ScheduledTask? = error("No ticker")
        override fun metrics() = PlatformDispatchMetrics(0, 0, 0, 0, 0, 0, 0, 0)
    }

    private class Owner(val name: String, server: () -> Server? = { null }) {
        @Volatile var enabled = true
        val plugin: Plugin = proxy(Plugin::class.java) { method, _ -> when (method) {
            "getName" -> name
            "isEnabled" -> enabled
            "getServer" -> server()
            else -> null
        } }
    }

    companion object {
        @JvmStatic @BeforeAll fun bootstrap() = ModelRuntimeTestSupport.bootstrap()
        private fun label(id: String, x: Double = 0.0, text: String = id) = EditorTextLabel(id, x, 64.0, 0.0, Component.text(text))
        private fun <T> proxy(type: Class<T>, handler: (String, Array<out Any>?) -> Any?): T = type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args -> when (method.name) {
                "equals" -> instance === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> type.simpleName
                else -> handler(method.name, args) ?: when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Double.TYPE -> 0.0
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            } }
        )
    }
}
