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
    private val record: (RoutePreviewSelection, RoutePreviewComputation, () -> Boolean) -> WandText,
    private val deletionChoices: (RoutePreviewSelection) -> List<RecordedRouteDeletion>,
    private val deleteRoute: (RecordedRouteDeletion, () -> Boolean) -> WandText,
    private val enabled: () -> Boolean = { plugin.isEnabled },
    worker: ((() -> Unit) -> Unit)? = null,
    private val entity: (Player, () -> Unit) -> Unit = { player, action -> player.scheduler.run(plugin, { action() }, {}) },
    private val repeatDisplay: (Player, () -> Unit, () -> Unit) -> AutoCloseable = { player, action, retired ->
        val task = player.scheduler.runAtFixedRate(plugin, { action() }, retired, 1L, RoutePreviewDrawing.REFRESH_TICKS)
        AutoCloseable { task?.cancel() }
    },
    private val inHand: (Player) -> Boolean = { readTool(plugin, it.inventory.itemInMainHand) },
    private val giveTool: (Player) -> Unit = { player ->
        require(player.inventory.firstEmpty() >= 0) { WandI18n.format(player, "inventory.full") }
        player.inventory.addItem(createItem(plugin, player.locale()))
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
            sender.sendMessage(Component.text(WandI18n.format(sender, "permission", SectionWandHandler.PERMISSION))); return true
        }
        if (sender !is Player) { sender.sendMessage(Component.text(WandI18n.format(sender, "route.player"))); return true }
        if (!args.contentEquals(arrayOf("wand", "route"))) {
            sender.sendMessage(Component.text("/simplesignals wand route")); return true
        }
        invalidate(sender.uniqueId)
        try {
            giveTool(sender)
            sender.sendMessage(Component.text(WandI18n.format(sender, "route.created")))
        } catch (failure: Exception) { sender.sendMessage(Component.text(failure.message ?: WandI18n.format(sender, "route.create.failed"))) }
        return true
    }

    private fun record(player: Player) {
        val locale = player.locale()
        val playerId = player.uniqueId
        val choice = choices[playerId]
        val selection = choice?.selection
        val session = choice?.preview
        val value = session?.visible()
        if (choice == null || selection?.exit == null || session == null || value == null ||
            value.path.end != CurrentRouteEnd.REACHED_EXIT || value.path.waitingForObservation ||
            choice.worldId != player.world.uid || !player.isOnline || !inHand(player)) {
            player.sendMessage(Component.text(WandI18n.format(locale, "route.preview.required")))
            return
        }
        if (!choice.busy.compareAndSet(false, true)) {
            player.sendMessage(Component.text(WandI18n.format(locale, "route.busy")))
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
                    reply(record.invoke(selection, value, ::active).render(locale))
                } catch (failure: Exception) {
                    reply("SimpleSignals: ${failure.message ?: failure.javaClass.simpleName}")
                }
            }
        } catch (failure: Exception) { reply(WandI18n.format(locale, "route.record.failed", failure.message)) }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || event.action == Action.PHYSICAL || !inHand(event.player)) return
        event.isCancelled = true
        val left = event.action == Action.LEFT_CLICK_AIR || event.action == Action.LEFT_CLICK_BLOCK
        if (!left && event.player.isSneaking) openRoutes(event.player) else click(event.player, left)
    }

    internal fun openRoutes(player: Player) {
        val locale = player.locale()
        val playerId = player.uniqueId
        val choice = choices[playerId]
        val selection = choice?.selection
        if (closed.get() || !enabled() || !player.hasPermission(SectionWandHandler.PERMISSION) ||
            !player.isOnline || !inHand(player) || choice == null || selection == null ||
            choice.worldId != player.world.uid) {
            player.sendMessage(Component.text(WandI18n.format(locale, "route.entry.required")))
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
            } } catch (failure: Exception) { reply(WandI18n.format(locale, "route.delete.failed", failure.message)) }
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
            require(routes.isNotEmpty()) { WandI18n.format(locale, "route.delete.empty") }
            val boundaries = routes.first().publication.definitions.regions.flatMap { it.boundaries }.associateBy { it.boundaryId }
            page(WandI18n.format(locale, "route.delete.title", authoringName(selection.regionId, "name.region")), routes.mapIndexed { index, route ->
                "${index + 1}. " + run {
                    fun label(id: String) = boundaries[id]?.let { backend.boundaryLabel(selection, it).render(locale) }
                        ?: authoringName(id, "name.entrance").render(locale)
                    "${label(route.route.entryBoundaryId)} → ${label(route.route.exitBoundaryId)}"
                } to {
                    page(WandI18n.format(locale, "route.delete.confirm", authoringName(route.route.routeId, "name.route")), listOf(
                        WandI18n.format(locale, "route.confirm.delete") to { reply(deleteRoute(route, ::active).render(locale)) },
                        WandI18n.format(locale, "cancel") to { reply(WandI18n.format(locale, "route.delete.cancelled")) }
                    ))
                }
            })
        }
    }

    internal fun click(player: Player, left: Boolean) {
        val locale = player.locale()
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
            player.sendMessage(Component.text(WandI18n.format(locale, "route.entry.first"))); return
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
                if (failure != null || target == null || target.isEmpty) { fail(WandI18n.format(locale, "route.boundary.none")); return@entity }
                val hit = target.get()
                val point = RailPositionSnapshot(hit.rail().id, ViewDirection.AB, hit.arcLength())
                val previous = if (selectingEntry) null else choice.selection
                worker {
                    if (!active()) return@worker
                    try {
                        val selection = backend.select(worldId, point, previous)
                        val entryLabel = backend.boundaryLabel(selection, selection.entry)
                        entity(player) {
                            if (!ownerCurrent()) { invalidate(playerId, choice); return@entity }
                            choice.selection = selection
                            choice.busy.set(false)
                            if (selectingEntry) player.sendMessage(Component.text(WandI18n.format(locale, "route.entry.selected", entryLabel)))
                            else {
                                choice.preview?.close()
                                choice.refresh?.close()
                                val session = CurrentRoutePreviewSession(selection, backend, worker, plugin.logger) { value -> entity(player) {
                                    if (ownerCurrent() && choice.preview?.visible() === value)
                                        player.sendMessage(Component.text(describe(value, locale)))
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
                                        fail(WandI18n.format(locale, "route.preview.failed", failure.message))
                                    }
                                }
                            }
                        }
                    } catch (failure: Exception) { fail("SimpleSignals: ${failure.message ?: failure.javaClass.simpleName}") }
                }
            } }
        } catch (failure: Exception) { fail(WandI18n.format(locale, "rail.failed", failure.message)) }
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
        private fun createItem(plugin: Plugin, locale: java.util.Locale): ItemStack = ItemStack(Material.BLAZE_ROD).apply { editMeta { meta ->
            meta.displayName(Component.text(WandI18n.format(locale, "route.name")))
            meta.persistentDataContainer.set(key(plugin), PersistentDataType.BYTE, 1.toByte())
            meta.lore(listOf(Component.text(WandI18n.format(locale, "route.lore"))))
        } }
        internal fun describe(value: RoutePreviewComputation, locale: java.util.Locale = java.util.Locale.US): String {
            val path = value.path
            val reached = path.reached
            return WandI18n.format(locale, "route.preview.result", path.end, path.detail, reached.facing, reached.arcLengthInAbMeters, path.traversals.size) +
                (if (value.drawing.points.any { it.kind == PreviewParticleKind.WARNING }) WandI18n.format(locale, "route.preview.warning") else "") +
                (if (value.drawing.batched) WandI18n.format(locale, "route.preview.batch", RoutePreviewDrawing.BUDGET) else "") +
                (if (value.drawing.unavailableGeometry.isNotEmpty()) WandI18n.format(locale, "route.preview.geometry", value.drawing.unavailableGeometry.size) else "")
        }
    }
}
