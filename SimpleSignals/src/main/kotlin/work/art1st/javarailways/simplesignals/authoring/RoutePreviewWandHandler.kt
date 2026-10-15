package work.art1st.javarailways.simplesignals.authoring

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.*
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import work.art1st.javarailways.bukkit.api.rail.RailTarget
import work.art1st.javarailways.bukkit.api.rail.RailTargetService
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class RoutePreviewWandHandler(private val plugin: Plugin, private val backend: RoutePreviewBackend,
    targets: RailTargetService,
    private val record: (RoutePreviewSelection, RoutePreviewComputation, () -> Boolean) -> String,
    private val deletionChoices: (RoutePreviewSelection) -> List<RecordedRouteDeletion>,
    private val deleteRoute: (RecordedRouteDeletion, () -> Boolean) -> String,
    private val enabled: () -> Boolean = { plugin.isEnabled },
    worker: ((() -> Unit) -> Unit)? = null,
    private val entity: (Player, () -> Unit) -> Unit = { player, action -> player.scheduler.run(plugin, { action() }, {}) },
    private val repeatDisplay: (Player, () -> Unit, () -> Unit) -> AutoCloseable = { player, action, retired ->
        val task = player.scheduler.runAtFixedRate(plugin, { action() }, retired, 1L, RoutePreviewDrawing.REFRESH_TICKS)
        AutoCloseable { task?.cancel() }
    },
    private val inHand: (Player) -> Boolean = { readTool(plugin, it.inventory.itemInMainHand) },
    private val giveTool: (Player) -> Unit = { player ->
        require(player.inventory.firstEmpty() >= 0) { "Make one empty inventory slot for the wand" }
        player.inventory.addItem(createItem(plugin))
    },
    private val findTarget: (Player) -> CompletionStage<Optional<RailTarget>> = { targets.findLookedAtRail(it, 64.0, 0.75) },
    private val render: (Player, RoutePreviewDrawing, Int) -> Unit = { player, drawing, offset -> drawing.draw(player, offset) },
    private val showAuthoringDialog: (Player, String, List<Pair<String, () -> Unit>>) -> Unit = WandDialogs::show
) : Listener, CommandExecutor, AutoCloseable {
    // Capture the scheduler once: observation callbacks enqueue values without reading Bukkit state.
    private val worker = worker ?: run {
        val scheduler = plugin.server.asyncScheduler
        { action: () -> Unit -> scheduler.runNow(plugin) { action() }; Unit }
    }
    private class Choice(val worldId: UUID) {
        val active = AtomicBoolean(true)
        val busy = AtomicBoolean(false)
        @Volatile var selection: RoutePreviewSelection? = null
        @Volatile var preview: CurrentRoutePreviewSession? = null
        @Volatile var refresh: AutoCloseable? = null
        @Volatile var dialogToken: Any? = null
        var rendered: RoutePreviewComputation? = null
        var offset = 0
        fun close() { active.set(false); preview?.close(); refresh?.close() }
    }
    private val choices = ConcurrentHashMap<UUID, Choice>()
    private val closed = AtomicBoolean(false)

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (closed.get() || !enabled()) return true
        if (!sender.hasPermission(SectionWandHandler.PERMISSION)) {
            sender.sendMessage(Component.text("SimpleSignals: missing permission ${SectionWandHandler.PERMISSION}")); return true
        }
        if (sender !is Player) { sender.sendMessage(Component.text("Route preview wand requires a player")); return true }
        if (!args.contentEquals(arrayOf("wand", "route"))) {
            sender.sendMessage(Component.text("/simplesignals wand route")); return true
        }
        invalidate(sender.uniqueId)
        try {
            giveTool(sender)
            sender.sendMessage(Component.text("Route wand: left-click an existing region entry; Shift-left-click its exit to preview; right-click saves/replaces; Shift-right opens route deletion. Physical connectivity does not grant movement authority."))
        } catch (failure: Exception) { sender.sendMessage(Component.text(failure.message ?: "Cannot create preview wand")) }
        return true
    }

    private fun record(player: Player) {
        val playerId = player.uniqueId
        val choice = choices[playerId]
        val selection = choice?.selection
        val session = choice?.preview
        val value = session?.visible()
        if (choice == null || selection?.exit == null || session == null || value == null ||
            value.path.end != CurrentRouteEnd.REACHED_EXIT || value.path.waitingForObservation ||
            choice.worldId != player.world.uid || !player.isOnline || !inHand(player)) {
            player.sendMessage(Component.text("Hold the route wand and select an entry and exit with a current complete preview first"))
            return
        }
        if (!choice.busy.compareAndSet(false, true)) {
            player.sendMessage(Component.text("Route selection or recording is already in progress"))
            return
        }
        // This guard is passed into the atomic transaction: only session values, never Bukkit reads.
        fun active() = !closed.get() && choice.active.get() && choices[playerId] === choice &&
            choice.selection === selection && choice.preview === session
        fun reply(message: String) = entity(player) {
            if (active() && enabled() && player.isOnline && player.world.uid == choice.worldId &&
                player.hasPermission(SectionWandHandler.PERMISSION) && inHand(player))
                player.sendMessage(Component.text(message.take(8192)))
            choice.busy.set(false)
        }
        try {
            worker {
                if (!active()) return@worker
                try {
                    require(session.visible() === value) { "Route preview expired; wait for the current path" }
                    reply(record.invoke(selection, value, ::active))
                } catch (failure: Exception) {
                    reply("SimpleSignals: ${failure.message ?: failure.javaClass.simpleName}")
                }
            }
        } catch (failure: Exception) { reply("Cannot start route recording: ${failure.message}") }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || event.action == Action.PHYSICAL || !inHand(event.player)) return
        event.isCancelled = true
        val left = event.action == Action.LEFT_CLICK_AIR || event.action == Action.LEFT_CLICK_BLOCK
        if (!left && event.player.isSneaking) openRoutes(event.player) else click(event.player, left)
    }

    internal fun openRoutes(player: Player) {
        val playerId = player.uniqueId
        val choice = choices[playerId]
        val selection = choice?.selection
        if (closed.get() || !enabled() || !player.hasPermission(SectionWandHandler.PERMISSION) ||
            !player.isOnline || !inHand(player) || choice == null || selection == null ||
            choice.worldId != player.world.uid) {
            player.sendMessage(Component.text("Select a region entry with the route wand first"))
            return
        }
        if (!choice.busy.compareAndSet(false, true)) return
        val token = Any()
        choice.dialogToken = token
        fun active() = !closed.get() && choice.active.get() && choices[playerId] === choice &&
            choice.selection === selection && choice.dialogToken === token
        fun ownerCurrent() = active() && enabled() && player.isOnline && player.world.uid == choice.worldId &&
            player.hasPermission(SectionWandHandler.PERMISSION) && inHand(player)
        fun reply(message: String) = entity(player) {
            if (ownerCurrent()) player.sendMessage(Component.text(message.take(8192)))
            choice.busy.set(false)
        }
        fun work(action: () -> Unit) {
            try { worker {
                if (!active()) { choice.busy.set(false); return@worker }
                try { action() } catch (failure: Exception) {
                    reply("SimpleSignals: ${failure.message ?: failure.javaClass.simpleName}")
                }
            } } catch (failure: Exception) { reply("Cannot start route deletion: ${failure.message}") }
        }
        fun page(title: String, options: List<Pair<String, () -> Unit>>) = entity(player) {
            choice.busy.set(false)
            if (!ownerCurrent()) return@entity
            val used = AtomicBoolean(false)
            showAuthoringDialog(player, title, options.map { (label, action) -> label to {
                entity(player) {
                    if (ownerCurrent() && used.compareAndSet(false, true) && choice.busy.compareAndSet(false, true))
                        work(action)
                }
            } })
        }
        work {
            val routes = deletionChoices(selection)
            require(routes.isNotEmpty()) { "This region has no authored routes to delete" }
            page("Delete route in ${selection.regionId}", routes.map { route ->
                "${route.route.routeId} | ${route.route.entryBoundaryId} -> ${route.route.exitBoundaryId}" to {
                    page("Delete ${route.route.routeId}?", listOf(
                        "Confirm deletion (keep devices)" to { reply(deleteRoute(route, ::active)) },
                        "Cancel" to { reply("Route deletion cancelled") }
                    ))
                }
            })
        }
    }

    internal fun click(player: Player, left: Boolean) {
        val playerId = player.uniqueId
        choices[playerId]?.dialogToken = null
        if (closed.get() || !enabled() || !player.hasPermission(SectionWandHandler.PERMISSION)) {
            invalidate(playerId); return
        }
        if (!left) { record(player); return }
        val selectingEntry = !player.isSneaking
        val worldId = player.world.uid
        val choice = if (selectingEntry) {
            invalidate(playerId)
            Choice(worldId).also { choices[playerId] = it }
        } else choices[playerId]?.takeIf { it.worldId == worldId && it.selection != null } ?: run {
            player.sendMessage(Component.text("Left-click a protection-region entry first")); return
        }
        if (!choice.busy.compareAndSet(false, true)) return
        fun active() = !closed.get() && choice.active.get() && choices[playerId] === choice
        fun ownerCurrent() = active() && enabled() && player.isOnline && player.world.uid == worldId &&
            player.hasPermission(SectionWandHandler.PERMISSION) && inHand(player)
        fun fail(message: String) = entity(player) {
            if (ownerCurrent()) player.sendMessage(Component.text(message.take(8192)))
            choice.busy.set(false)
        }
        try {
            findTarget(player).whenComplete { target, failure -> entity(player) {
                if (!ownerCurrent()) { invalidate(playerId, choice); return@entity }
                if (failure != null || target == null || target.isEmpty) { fail("No existing region boundary selected"); return@entity }
                val hit = target.get()
                val point = RailPositionSnapshot(hit.rail().id, ViewDirection.AB, hit.arcLength())
                val previous = if (selectingEntry) null else choice.selection
                worker {
                    if (!active()) return@worker
                    try {
                        val selection = backend.select(worldId, point, previous)
                        entity(player) {
                            if (!ownerCurrent()) { invalidate(playerId, choice); return@entity }
                            choice.selection = selection
                            choice.busy.set(false)
                            if (selectingEntry) player.sendMessage(Component.text("Entry ${selection.entry.boundaryId} selected; Shift-left-click the desired exit"))
                            else {
                                choice.preview?.close()
                                choice.refresh?.close()
                                val session = CurrentRoutePreviewSession(selection, backend, worker) { value -> entity(player) {
                                    if (ownerCurrent() && choice.preview?.visible() === value)
                                        player.sendMessage(Component.text(describe(value)))
                                } }
                                choice.preview = session
                                choice.refresh = repeatDisplay(player, {
                                    if (!ownerCurrent()) invalidate(playerId, choice)
                                    else choice.preview?.visible()?.let { value ->
                                        if (choice.rendered !== value) { choice.rendered = value; choice.offset = 0 }
                                        render(player, value.drawing, choice.offset)
                                        choice.offset = value.drawing.nextOffset(choice.offset)
                                    }
                                }, { invalidate(playerId, choice) })
                                // Unload/retirement may run while the owner attaches these handles.
                                if (!active()) choice.close()
                                else worker {
                                    if (active()) try { session.start() } catch (failure: Exception) {
                                        fail("Route preview unavailable: ${failure.message}")
                                    }
                                }
                            }
                        }
                    } catch (failure: Exception) { fail("SimpleSignals: ${failure.message ?: failure.javaClass.simpleName}") }
                }
            } }
        } catch (failure: Exception) { fail("Rail selection failed: ${failure.message}") }
    }

    private fun invalidate(id: UUID) { choices.remove(id)?.close() }
    private fun invalidate(id: UUID, choice: Choice) {
        choices.remove(id, choice)
        choice.close()
    }
    @EventHandler fun onQuit(event: PlayerQuitEvent) = invalidate(event.player.uniqueId)
    @EventHandler fun onWorldChange(event: PlayerChangedWorldEvent) = invalidate(event.player.uniqueId)
    @EventHandler(ignoreCancelled = true) fun onHeldItem(event: PlayerItemHeldEvent) = invalidate(event.player.uniqueId)
    @EventHandler(ignoreCancelled = true) fun onSwapHands(event: PlayerSwapHandItemsEvent) = invalidate(event.player.uniqueId)
    @EventHandler(ignoreCancelled = true) fun onInventoryClick(event: InventoryClickEvent) = invalidate(event.whoClicked.uniqueId)
    @EventHandler(ignoreCancelled = true) fun onDrop(event: PlayerDropItemEvent) = invalidate(event.player.uniqueId)
    @EventHandler(ignoreCancelled = true) fun onWorldUnload(event: WorldUnloadEvent) {
        choices.forEach { (id, choice) -> if (choice.worldId == event.world.uid) invalidate(id, choice) }
    }
    override fun close() { closed.set(true); choices.keys.forEach(::invalidate) }

    companion object {
        private fun key(plugin: Plugin) = NamespacedKey(plugin, "route_preview_wand")
        private fun readTool(plugin: Plugin, stack: ItemStack): Boolean =
            stack.itemMeta?.persistentDataContainer?.get(key(plugin), PersistentDataType.BYTE) == 1.toByte()
        private fun createItem(plugin: Plugin): ItemStack = ItemStack(Material.BLAZE_ROD).apply { editMeta { meta ->
            meta.displayName(Component.text("SimpleSignals current route preview"))
            meta.persistentDataContainer.set(key(plugin), PersistentDataType.BYTE, 1.toByte())
            meta.lore(listOf(Component.text("Left: entry; Shift-left: exit; right: save/replace; Shift-right: delete route")))
        } }
        internal fun describe(value: RoutePreviewComputation): String {
            val path = value.path
            val reached = path.reached
            return "${path.end}: ${path.detail}. Actual end: ${reached.railId} ${reached.facing} @ ${reached.arcLengthInAbMeters} m; " +
                "${path.traversals.size} directed rail spans. Cyan: entry; yellow: requested exit; raised ${if (value.drawing.connected) "green" else "red"}: actual end; white arrows: travel direction. " +
                "Physical connectivity only; no movement authority." +
                (if (value.drawing.points.any { it.kind == PreviewParticleKind.WARNING }) " Amber ! at boundaries: multiple legal paths; shortest initially selected; right-click can replace the selected pair with this preview." else "") +
                (if (value.drawing.batched) " Long display rotates in batches of at most ${RoutePreviewDrawing.BUDGET} particles." else "") +
                (if (value.drawing.unavailableGeometry.isNotEmpty()) " Cannot draw missing geometry on ${value.drawing.unavailableGeometry.size} rails." else "")
        }
    }
}
