package work.art1st.javarailways.simplesignals.authoring

import net.kyori.adventure.text.Component
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataContainer
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
import work.art1st.javarailways.bukkit.api.model.EditorModelHighlightHandle
import work.art1st.javarailways.bukkit.api.editor.EditorTextHandle
import work.art1st.javarailways.bukkit.api.editor.EditorTextLabel
import work.art1st.javarailways.bukkit.api.editor.EditorTextService
import work.art1st.javarailways.simplesignals.authoring.RuntimeRoutePreviewBackendTest.Companion.proxy
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Actual overlay consumer, deterministic owner/worker queues; host service entity semantics have separate tests. */
class SectionWandOverlayTest {
    @Test fun `owner validation exception is reported without preventing the next refresh`() {
        val f = Fixture()
        val failure = IllegalStateException("topology unavailable")
        f.ownerValidationFailure = failure
        f.start()
        assertSame(failure, f.records.single().thrown)
        f.ownerValidationFailure = null
        f.tick(); f.drainWorker(); f.drainOwner()
        assertEquals(1, f.records.size)
        f.overlay.close()
    }
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

    @Test fun `route wand marker starts section overlay and switching tools keeps its labels until leaving`() {
        val f = Fixture()
        f.tool = null
        f.routeTool = true
        f.start()
        assertEquals(listOf("near"), f.handles.single().labels.map { it.id() })
        assertEquals(1, f.prepares)
        f.routeTool = false
        f.tool = SectionWandTool.CREATE
        f.overlay.check(f.player); f.drainOwner(); f.drainWorker(); f.drainOwner()
        assertEquals(1, f.handles.size)
        assertEquals(1, f.prepares)
        f.tool = null
        f.routeTool = true
        f.overlay.check(f.player); f.drainOwner(); f.drainWorker(); f.drainOwner()
        assertFalse(f.handles.single().closed)
        f.routeTool = false
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
        assertTrue(f.highlights[0].closed)
        assertFalse(f.highlights[1].closed)
        assertEquals(f.viewers, f.highlightViewers)
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
            assertTrue(f.highlights.single().closed)
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
        assertEquals(-1L, f.highlights.single().revision)
        val g = Fixture()
        g.overlay.check(g.player); g.drainOwner(); g.drainWorker()
        val oldTask = g.refreshes[g.refreshes.lastIndex]
        g.tool = null; g.tick()
        g.tool = SectionWandTool.DELETE
        g.overlay.check(g.player); g.drainOwner(); g.drainWorker(); g.drainOwner()
        assertTrue(g.handles.first().closed)
        assertTrue(g.highlights.first().closed)
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
        f.tool = SectionWandTool.CREATE
        f.retireOnAttach = true
        f.overlay.check(f.player); f.drainOwner(); f.drainWorker(); f.drainOwner()
        assertTrue(f.handles.single().closed)
        assertEquals(1, f.cancelled)
        assertEquals(-1L, f.handles.single().revision)
    }

    @Test fun `live faults update highlight without rebuilding geometry and identical IDs coalesce`() {
        val f = Fixture()
        f.problemModels = setOf("protection-signal/test:broken")
        f.start()
        val highlight = f.highlights.single()
        assertEquals(f.problemModels, highlight.models)
        assertEquals(0xFF0000, highlight.rgb)
        val revision = highlight.revision
        f.tick(); f.drainWorker(); f.drainOwner()
        assertEquals(revision, highlight.revision)
        f.problemModels = emptySet()
        f.tick(); f.drainWorker(); f.drainOwner()
        assertTrue(highlight.models.isEmpty())
        assertTrue(highlight.revision > revision)
        assertEquals(1, f.prepares)
        f.overlay.close()
    }

    @Test fun `failed diagnostics clears stale glow and preparation may recover next refresh`() {
        val f = Fixture()
        f.problemModels = setOf("fixed-block-signal/test:broken")
        f.start()
        f.problemFailure = IllegalStateException("issue read failed")
        f.tick(); f.drainWorker(); f.drainOwner()
        assertTrue(f.highlights.single().models.isEmpty())
        f.problemFailure = null
        f.tick(); f.drainWorker(); f.drainOwner()
        assertEquals(f.problemModels, f.highlights.single().models)
        f.overlay.close()
    }

    @Test fun `highlight construction failure closes text and cleanup failure does not retain other handles`() {
        val f = Fixture()
        f.highlightCreateFailure = true
        f.overlay.check(f.player)
        assertThrows(IllegalStateException::class.java) { f.drainOwner() }
        assertTrue(f.handles.single().closed)
        val g = Fixture()
        g.start()
        g.handles.single().closeFailure = true
        g.overlay.close()
        assertTrue(g.highlights.single().closed)
        assertEquals(1, g.cancelled)
    }

    private class Highlight : EditorModelHighlightHandle {
        var closed = false
        var revision = -1L
        var models = emptySet<String>()
        var rgb = -1
        override fun submit(revision: Long, objectIds: Set<String>, rgb: Int) {
            check(!closed); check(revision > this.revision)
            this.revision = revision; models = objectIds.toSet(); this.rgb = rgb
        }
        override fun close() { closed = true; models = emptySet() }
    }

    private class Handle : EditorTextHandle {
        var closed = false
        var revision = -1L
        var labels = emptyList<EditorTextLabel>()
        var closeFailure = false
        override fun submit(revision: Long, labels: List<EditorTextLabel>) {
            check(!closed); check(revision > this.revision)
            this.revision = revision; this.labels = labels.toList()
        }
        override fun close() { closed = true; labels = emptyList(); if (closeFailure) error("text close failed") }
    }
    private class Fixture {
        var ownerThread = true
        var enabled = true
        var permitted = true
        var online = true
        var x = 0.0
        var tool: SectionWandTool? = SectionWandTool.CREATE
        var available = true
        var token = capture()
        var prepares = 0
        var problemModels = emptySet<String>()
        var problemFailure: Exception? = null
        var highlightCreateFailure = false
        val highlights = arrayListOf<Highlight>()
        val highlightViewers = arrayListOf<Player>()
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
        var routeTool = false
        var ownerValidationFailure: Exception? = null
        val records = mutableListOf<java.util.logging.LogRecord>()
        private val logger = java.util.logging.Logger.getAnonymousLogger().apply {
            useParentHandlers = false
            addHandler(object : java.util.logging.Handler() {
                override fun publish(record: java.util.logging.LogRecord) { records += record }
                override fun flush() = Unit
                override fun close() = Unit
            })
        }
        val offline = arrayListOf<Player>()
        val player = player(UUID(0, 2))
        private val plugin = proxy(Plugin::class.java) { name, _ -> when (name) {
            "getLogger" -> logger
            "namespace" -> "simplesignals"
            else -> error("Unexpected plugin access $name")
        } }
        private val data = proxy(PersistentDataContainer::class.java) { name, args ->
            check(ownerThread)
            check(name == "get")
            when ((args!![0] as NamespacedKey).key) {
                "section_wand" -> tool?.let { SectionWandStateCodec.encode(SectionWandState(UUID(0, 3), 0, it, true)) }
                "signal_wand" -> if (signalTool) 1.toByte() else null
                "signal_wand_state" -> if (signalTool) SignalWandHandler.encodeState(SignalWandState(
                    UUID(0, 4), 0, SignalWandMode.PLACEMENT, SignalWandControlKind.FIXED_BLOCK, "test:type", null)) else null
                "route_preview_wand" -> if (routeTool) 1.toByte() else null
                else -> error("Unexpected item key ${args[0]}")
            }
        }
        private val meta = proxy(ItemMeta::class.java) { name, _ ->
            if (name == "getPersistentDataContainer") data else error(name)
        }
        private val item = object : ItemStack() { override fun getItemMeta(): ItemMeta = meta }
        private val inventory = proxy(PlayerInventory::class.java) { name, _ ->
            if (name == "getItemInMainHand") item else error(name)
        }
        fun newWorld() = proxy(World::class.java) { name, _ ->
            check(ownerThread) { "World accessed by worker" }
            if (name == "getUID") UUID(0, 1) else error(name)
        }
        fun player(id: UUID): Player {
            lateinit var viewer: Player
            viewer = proxy(Player::class.java) { name, _ ->
                check(ownerThread) { "Player accessed by worker: $name" }
                when (name) {
                    "locale" -> java.util.Locale.US
                    "getUniqueId" -> id
                    "isOnline" -> online && viewer !in offline
                    "hasPermission" -> permitted
                    "getWorld" -> world
                    "getInventory" -> inventory
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
            override fun current(capture: SectionOverlayCapture): Boolean {
                if (ownerThread) ownerValidationFailure?.let { throw it }
                return available && capture === token
            }
            override fun problemModels(capture: SectionOverlayCapture): Set<String> {
                check(!ownerThread)
                problemFailure?.let { throw it }
                return problemModels
            }
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
            createHighlight = { viewer ->
                check(ownerThread)
                if (highlightCreateFailure) error("highlight create failed")
                highlightViewers += viewer
                Highlight().also { highlights += it }
            },
            enabled = { check(ownerThread); enabled },
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
