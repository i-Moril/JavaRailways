package work.art1st.javarailways.simplesignals.authoring

import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.editor.EditorTextHandle
import work.art1st.javarailways.bukkit.api.editor.EditorTextLabel
import work.art1st.javarailways.bukkit.api.editor.EditorTextService
import work.art1st.javarailways.simplesignals.authoring.RuntimeRoutePreviewBackendTest.Companion.proxy
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Actual overlay consumer, deterministic owner/worker queues; host service entity semantics have separate tests. */
class SectionWandOverlayTest {
    @Test fun `block to signal tool reuses prepared capture and one handle until leaving editor tools`() {
        val f = Fixture(); f.start()
        f.tool = null; f.signalTool = true
        f.overlay.check(f.player); f.drainOwner(); f.drainWorker(); f.drainOwner()
        assertEquals(1, f.handles.size)
        assertEquals(1, f.prepares)
        assertFalse(f.handles.single().closed)
        f.signalTool = false
        f.overlay.check(f.player); f.drainOwner()
        assertTrue(f.handles.single().closed)
        assertEquals(1, f.cancelled)
    }

    @Test fun `held event starts overlay and stable refresh reuses geometry while filtering current owner position`() {
        val f = Fixture()
        f.overlay.onHeld(PlayerItemHeldEvent(f.player, 0, 1))
        assertTrue(f.handles.isEmpty()) // Event handler defers until inventory has actually changed.
        f.drainOwner(); f.drainWorker(); f.drainOwner()
        assertEquals(1, f.prepares)
        assertEquals(1, f.handles.size)
        assertEquals(listOf("near"), f.handles.single().labels.map { it.id() })
        f.x = 100.0
        f.tick(); f.drainWorker(); f.drainOwner()
        assertEquals(1, f.prepares)
        assertEquals(listOf("far"), f.handles.single().labels.map { it.id() })
        f.token = f.capture()
        f.tick(); f.drainWorker(); f.drainOwner()
        assertEquals(2, f.prepares)
    }

    @Test fun `two players receive separate text handles through their own owner`() {
        val f = Fixture()
        val other = f.player(UUID(0, 9))
        f.overlay.check(f.player); f.overlay.check(other)
        f.drainOwner(); f.drainWorker(); f.drainOwner()
        assertEquals(2, f.handles.size)
        assertEquals(setOf(f.player, other), f.viewers.toSet())
        f.overlay.onQuit(PlayerQuitEvent(f.player, Component.empty()))
        assertTrue(f.handles[0].closed)
        assertFalse(f.handles[1].closed)
    }

    @Test fun `permission loss unhold world unload and sameUUID world replacement close handles`() {
        for (stop in listOf<(Fixture) -> Unit>(
            { it.permitted = false; it.tick() },
            { it.tool = null; it.tick() },
            { it.overlay.onUnload(WorldUnloadEvent(it.world)) },
            { it.online = false; it.tick() },
            { it.overlay.close() },
            { it.retired[it.retired.lastIndex].invoke() }
        )) {
            val f = Fixture(); f.start()
            stop(f)
            assertTrue(f.handles.single().closed)
            val revision = f.handles.single().revision
            f.tick(); f.drainWorker(); f.drainOwner()
            assertEquals(revision, f.handles.single().revision)
        }
        val f = Fixture(); f.start()
        val old = f.world
        f.world = f.newWorld() // Same UUID, distinct loaded-world instance.
        f.overlay.onWorldChange(PlayerChangedWorldEvent(f.player, old))
        assertTrue(f.handles.single().closed)
        f.drainOwner(); f.drainWorker(); f.drainOwner()
        assertEquals(2, f.handles.size)
        assertFalse(f.handles.last().closed)
    }

    @Test fun `late worker and queued owner cannot revive a closed overlay or replace its successor`() {
        val f = Fixture()
        f.overlay.check(f.player); f.drainOwner()
        f.overlay.close(); f.drainWorker(); f.drainOwner()
        assertTrue(f.handles.single().closed)
        assertEquals(0, f.prepares)
        assertEquals(-1L, f.handles.single().revision)
        val g = Fixture()
        g.overlay.check(g.player); g.drainOwner(); g.drainWorker()
        val oldTask = g.refreshes[g.refreshes.lastIndex]
        g.tool = null; g.tick()
        g.tool = SectionWandTool.REGION_DELETE
        g.overlay.check(g.player); g.drainOwner(); g.drainWorker(); g.drainOwner()
        assertTrue(g.handles.first().closed)
        assertFalse(g.handles.last().closed)
        val revision = g.handles.last().revision
        oldTask()
        g.drainWorker(); g.drainOwner()
        assertEquals(revision, g.handles.last().revision)
        assertEquals(2, g.handles.size)
    }

    @Test fun `unavailable world clears previous labels and retries on next refresh without worker spin`() {
        val f = Fixture(); f.start()
        f.available = false
        f.tick(); f.drainWorker(); f.drainOwner()
        assertTrue(f.handles.single().labels.single().text().toString().contains("unavailable"))
        assertTrue(f.workers.isEmpty())
        f.available = true
        f.tick(); f.drainWorker(); f.drainOwner()
        assertEquals(2, f.prepares)
        assertEquals("near", f.handles.single().labels.single().id())
        // An input changed after preparation but before owner delivery: reject its drawing.
        f.token = f.capture(); f.tick(); f.drainWorker(); f.token = f.capture(); f.drainOwner()
        assertTrue(f.handles.single().labels.single().text().toString().contains("unavailable"))
        assertTrue(f.workers.isEmpty())
    }

    @Test fun `old viewer callbacks cannot close a reconnected viewer with the same UUID`() {
        val f = Fixture(); f.start()
        f.overlay.check(f.player)
        val oldRefresh = f.owners.removeFirst()
        f.offline += f.player
        f.overlay.onQuit(PlayerQuitEvent(f.player, Component.empty()))
        val replacement = f.player(f.player.uniqueId)
        f.overlay.check(replacement); f.drainOwner(); f.drainWorker(); f.drainOwner()
        val current = f.handles.last()
        assertFalse(current.closed)
        oldRefresh()
        f.overlay.check(f.player)
        f.ownerRetirements[f.ownerRetirements.lastIndex].invoke()
        f.overlay.onQuit(PlayerQuitEvent(f.player, Component.empty()))
        f.drainOwner(); f.drainWorker(); f.drainOwner()
        assertFalse(current.closed)
        assertEquals(2, f.handles.size)
        f.overlay.onQuit(PlayerQuitEvent(replacement, Component.empty()))
        assertTrue(current.closed)
    }

    @Test fun `retirement during repeating task attachment closes late handle and join without tool stays idle`() {
        val f = Fixture()
        f.tool = null
        f.overlay.onJoin(PlayerJoinEvent(f.player, Component.empty())); f.drainOwner()
        assertTrue(f.handles.isEmpty())
        f.tool = SectionWandTool.BLOCK_CREATE
        f.retireOnAttach = true
        f.overlay.check(f.player); f.drainOwner(); f.drainWorker(); f.drainOwner()
        assertTrue(f.handles.single().closed)
        assertEquals(1, f.cancelled)
        assertEquals(-1L, f.handles.single().revision)
    }

    private class Handle : EditorTextHandle {
        var closed = false
        var revision = -1L
        var labels = emptyList<EditorTextLabel>()
        override fun submit(revision: Long, labels: List<EditorTextLabel>) {
            check(!closed); check(revision > this.revision)
            this.revision = revision; this.labels = labels.toList()
        }
        override fun close() { closed = true; labels = emptyList() }
    }
    private class Fixture {
        var ownerThread = true
        var enabled = true
        var permitted = true
        var online = true
        var x = 0.0
        var tool: SectionWandTool? = SectionWandTool.BLOCK_CREATE
        var available = true
        var token = capture()
        var prepares = 0
        var cancelled = 0
        var retireOnAttach = false
        var world = newWorld()
        val owners = ArrayDeque<() -> Unit>()
        val ownerRetirements = arrayListOf<() -> Unit>()
        val workers = ArrayDeque<() -> Unit>()
        val refreshes = arrayListOf<() -> Unit>()
        val retired = arrayListOf<() -> Unit>()
        val handles = arrayListOf<Handle>()
        val viewers = arrayListOf<Player>()
        var signalTool = false
        val offline = arrayListOf<Player>()
        val player = player(UUID(0, 2))
        private val plugin = proxy(Plugin::class.java) { name, _ -> error("Unexpected plugin access $name") }
        fun newWorld() = proxy(World::class.java) { name, _ ->
            check(ownerThread) { "World accessed by worker" }
            if (name == "getUID") UUID(0, 1) else error(name)
        }
        fun player(id: UUID): Player {
            lateinit var viewer: Player
            viewer = proxy(Player::class.java) { name, _ ->
                check(ownerThread) { "Player accessed by worker: $name" }
                when (name) {
                    "getUniqueId" -> id
                    "isOnline" -> online && viewer !in offline
                    "hasPermission" -> permitted
                    "getWorld" -> world
                    "getLocation" -> Location(world, x, 0.0, 0.0)
                    else -> error(name)
                }
            }
            return viewer
        }
        fun capture() = object : SectionOverlayCapture {}
        private val backend = object : SectionOverlayBackend {
            override fun capture(worldId: UUID): SectionOverlayCapture? {
                check(!ownerThread)
                return token.takeIf { available }
            }
            override fun current(capture: SectionOverlayCapture) = available && capture === token
            override fun prepare(capture: SectionOverlayCapture): SectionOverlayDrawing {
                check(!ownerThread); prepares++
                return SectionOverlayDrawing(listOf(EditorTextLabel("near", 0.0, 1.0, 0.0, Component.text("near")),
                        EditorTextLabel("far", 100.0, 1.0, 0.0, Component.text("far"))), emptySet())
            }
        }
        private val texts = object : EditorTextService {
            override fun create(owner: Plugin, viewer: Player, objectId: String): EditorTextHandle {
                check(ownerThread); viewers += viewer
                return Handle().also { handles += it }
            }
            override fun closeOwner(owner: Plugin) = CompletableFuture.completedFuture<Void>(null)
        }
        val overlay = SectionWandOverlay(plugin, backend, texts,
            toolInHand = { check(ownerThread); tool != null || signalTool }, enabled = { check(ownerThread); enabled },
            worker = { workers += it }, owner = { _, action, onRetired -> owners += action; ownerRetirements += onRetired },
            repeat = { _, action, onRetired ->
                check(ownerThread); refreshes += action; retired += onRetired
                if (retireOnAttach) onRetired()
                AutoCloseable { cancelled++ }
            })
        fun drainOwner() { ownerThread = true; while (owners.isNotEmpty()) owners.removeFirst().invoke() }
        fun drainWorker() {
            ownerThread = false
            try { while (workers.isNotEmpty()) workers.removeFirst().invoke() } finally { ownerThread = true }
        }
        fun tick() { ownerThread = true; refreshes[refreshes.lastIndex].invoke() }
        fun start() { overlay.check(player); drainOwner(); drainWorker(); drainOwner() }
    }
}
