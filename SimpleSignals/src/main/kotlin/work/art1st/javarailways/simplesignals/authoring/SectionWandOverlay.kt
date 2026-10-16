package work.art1st.javarailways.simplesignals.authoring

import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.*
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.plugin.Plugin
import work.art1st.javarailways.bukkit.api.editor.EditorTextHandle
import work.art1st.javarailways.bukkit.api.editor.EditorTextService
import work.art1st.javarailways.bukkit.api.editor.EditorTextLabel
import net.kyori.adventure.text.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Holding intent and prepared values only; host service owns every text entity and its cleanup. */
internal class SectionWandOverlay(
    private val plugin: Plugin,
    private val backend: SectionOverlayBackend,
    private val texts: EditorTextService,
    private val toolInHand: (Player) -> Boolean = {
        SectionWandHandler.readTool(plugin, it.inventory.itemInMainHand) != null ||
            SignalWandHandler.readTool(plugin, it.inventory.itemInMainHand) != null
    },
    private val enabled: () -> Boolean = { plugin.isEnabled },
    private val worker: (() -> Unit) -> Unit = { action -> plugin.server.asyncScheduler.runNow(plugin) { action() } },
    private val owner: (Player, () -> Unit, () -> Unit) -> Unit = { player, action, retired ->
        if (player.scheduler.run(plugin, { action() }, retired) == null) retired()
    },
    private val repeat: (Player, () -> Unit, () -> Unit) -> AutoCloseable = { player, action, retired ->
        val task = player.scheduler.runAtFixedRate(plugin, { action() }, retired, 1L, RoutePreviewDrawing.REFRESH_TICKS)
        if (task == null) retired()
        AutoCloseable { task?.cancel() }
    }
) : Listener, AutoCloseable {
    private class Session(val viewer: Player, val world: World, val worldId: UUID,
        val text: EditorTextHandle) {
        val active = AtomicBoolean(true)
        val busy = AtomicBoolean(false)
        @Volatile var refresh: AutoCloseable? = null
        @Volatile var capture: SectionOverlayCapture? = null
        @Volatile var drawing: SectionOverlayDrawing? = null
        var revision = 0L
        var failure: String? = null
        fun close() { active.set(false); try { refresh?.close() } finally { text.close() } }
    }
    private val closed = AtomicBoolean(false)
    private val sessions = ConcurrentHashMap<UUID, Session>()

    /** May be called from event callbacks before inventory mutation; defer the actual owner capture. */
    fun check(player: Player) {
        if (closed.get()) return
        val id = player.uniqueId
        val previous = sessions[id]?.takeIf { it.viewer === player }
        owner(player, { refresh(player) }, { if (previous != null) invalidate(id, previous) })
    }

    private fun refresh(player: Player) {
        val id = player.uniqueId
        val tool = if (!closed.get() && enabled() && player.isOnline && player.hasPermission(SectionWandHandler.PERMISSION))
            toolInHand(player) else false
        if (!tool) { invalidate(player); return }
        val world = player.world
        var session = sessions[id]
        if (session == null || session.viewer !== player || session.world !== world) {
            invalidate(id)
            val text = texts.create(plugin, player, "section-overlay")
            val next = Session(player, world, world.uid, text)
            sessions[id] = next
            session = next
            try {
                next.refresh = repeat(player, { if (sessions[id] === next && next.active.get()) refresh(player) }, { invalidate(id, next) })
                if (closed.get() || !next.active.get()) { invalidate(id, next); next.close(); return }
            } catch (failure: Exception) { invalidate(id, next); throw failure }
        }
        val current = session
        // World/tool are captured on player owner. Workers never dereference World or Player.
        if (current.busy.compareAndSet(false, true)) {
            try {
                worker {
                    if (!current.active.get() || closed.get()) { current.busy.set(false); return@worker }
                    try {
                        val previous = current.capture
                        val capture = if (previous != null && backend.current(previous)) previous else backend.capture(current.worldId)
                        val drawing = if (capture == null) null else if (capture === previous) current.drawing else backend.prepare(capture)
                        val valid = capture == null || backend.current(capture)
                        // No eager retry after a changing input; the next refresh captures again.
                        owner(player, {
                            if (sessions[id] === current && current.active.get() && !closed.get()) {
                                if (player.isOnline && player.world === world && player.hasPermission(SectionWandHandler.PERMISSION) &&
                                    toolInHand(player) && enabled()) {
                                    val stillCurrent = valid && (capture == null || runCatching { backend.current(capture) }.getOrDefault(false))
                                    current.capture = capture.takeIf { stillCurrent }
                                    current.drawing = drawing.takeIf { stillCurrent }
                                    current.failure = null
                                    val position = player.location
                                    display(current, position.x, position.y, position.z)
                                } else invalidate(id, current)
                            }
                            current.busy.set(false)
                        }, { current.busy.set(false); invalidate(id, current) })
                    } catch (failure: Exception) {
                        val reason = (failure.message ?: failure.javaClass.simpleName).take(256)
                        owner(player, {
                            if (sessions[id] === current && current.active.get() && !closed.get() &&
                                player.isOnline && player.world === world && enabled() &&
                                player.hasPermission(SectionWandHandler.PERMISSION) && toolInHand(player)) {
                                current.capture = null; current.drawing = null
                                current.failure = reason
                                val position = player.location
                                display(current, position.x, position.y, position.z)
                            } else invalidate(id, current)
                            current.busy.set(false)
                        }, { current.busy.set(false); invalidate(id, current) })
                    }
                }
            } catch (_: Exception) { current.busy.set(false); invalidate(id, current) }
        }
    }

    private fun display(session: Session, x: Double, y: Double, z: Double) {
        val prepared = session.drawing
        val near = prepared?.nearby(x, y, z)
        val labels = near?.localizedLabels(session.viewer.locale()).orEmpty().toMutableList()
        if (prepared == null || prepared.unavailableGeometry.isNotEmpty()) labels += EditorTextLabel("geometry-status",
            x, y + 2.0, z, Component.text(if (prepared == null) WandI18n.format(session.viewer, "overlay.failed", session.failure.orEmpty()) else
                WandI18n.format(session.viewer, "overlay.geometry", prepared.unavailableGeometry.size)))
        session.text.submit(++session.revision, labels)
    }

    private fun invalidate(id: UUID) { sessions.remove(id)?.close() }
    private fun invalidate(id: UUID, session: Session) { if (sessions.remove(id, session)) session.close() }
    private fun invalidate(player: Player) {
        val id = player.uniqueId
        sessions[id]?.takeIf { it.viewer === player }?.let { invalidate(id, it) }
    }
    @EventHandler fun onJoin(event: PlayerJoinEvent) = check(event.player)
    @EventHandler fun onQuit(event: PlayerQuitEvent) = invalidate(event.player)
    @EventHandler fun onWorldChange(event: PlayerChangedWorldEvent) { invalidate(event.player); check(event.player) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun onHeld(event: PlayerItemHeldEvent) = check(event.player)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun onSwap(event: PlayerSwapHandItemsEvent) = check(event.player)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun onInventory(event: InventoryClickEvent) { (event.whoClicked as? Player)?.let(::check) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun onDrag(event: InventoryDragEvent) { (event.whoClicked as? Player)?.let(::check) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun onDrop(event: PlayerDropItemEvent) = check(event.player)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun onPickup(event: EntityPickupItemEvent) { (event.entity as? Player)?.let(::check) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun onUnload(event: WorldUnloadEvent) {
        sessions.forEach { (id, session) -> if (session.world === event.world) invalidate(id, session) }
    }
    override fun close() { closed.set(true); sessions.keys.forEach(::invalidate) }
}
