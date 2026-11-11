package work.art1st.javarailways.simplesignals.authoring

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.*
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import work.art1st.javarailways.bukkit.api.rail.RailTarget
import work.art1st.javarailways.bukkit.api.rail.RailTargetService
import java.util.Locale
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Two-point, read-only measurement; player state stays on its owner, all geometry/search on worker. */
internal class MeasurementWandHandler(
    private val plugin: Plugin, private val backend: RailMeasurementBackend, private val targets: RailTargetService,
    private val enabled: () -> Boolean = { plugin.isEnabled },
    private val worker: (() -> Unit) -> Unit = { action -> plugin.server.asyncScheduler.runNow(plugin) { action() } },
    private val owner: (Player, () -> Unit, () -> Unit) -> Unit = { player, action, retired ->
        if (player.scheduler.run(plugin, { action() }, retired) == null) retired()
    },
    private val held: (Player) -> Boolean = { isTool(plugin, it.inventory.itemInMainHand) },
    private val give: (Player) -> Unit = {
        require(it.inventory.firstEmpty() >= 0) { WandI18n.format(it, "inventory.full") }
        it.inventory.addItem(createItem(plugin, it.locale()))
    },
    private val target: (Player) -> CompletionStage<Optional<RailTarget>> = { targets.findLookedAtRail(it, 64.0, 0.75) }
) : Listener, CommandExecutor, AutoCloseable {
    private class Session(val viewer: Player, val world: World, val worldId: UUID) {
        val active = AtomicBoolean(true)
        val busy = AtomicBoolean(false)
        var capture: RailMeasurementCapture? = null
        var first: MeasuredRailPoint? = null
    }
    private val closed = AtomicBoolean(false)
    private val sessions = ConcurrentHashMap<UUID, Session>()

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!enabled() || closed.get()) return true
        if (!sender.hasPermission(SectionWandHandler.PERMISSION)) {
            sender.sendMessage(Component.text(WandI18n.format(sender, "permission", SectionWandHandler.PERMISSION))); return true
        }
        if (sender !is Player) { sender.sendMessage(Component.text(WandI18n.format(sender, "measure.player"))); return true }
        if (args.toList() != listOf("wand", "measure")) {
            sender.sendMessage(Component.text("/simplesignals wand measure")); return true
        }
        invalidate(sender)
        try {
            give(sender)
            sender.sendMessage(Component.text(WandI18n.format(sender, "measure.created")))
        } catch (failure: Exception) { sender.sendMessage(Component.text(failure.message ?: WandI18n.format(sender, "measure.create.failed"))) }
        return true
    }

    @EventHandler fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || event.action == Action.PHYSICAL || !held(event.player)) return
        event.isCancelled = true
        click(event.player, event.action == Action.LEFT_CLICK_AIR || event.action == Action.LEFT_CLICK_BLOCK)
    }

    internal fun click(player: Player, leftClick: Boolean = false) {
        val locale = player.locale()
        val id = player.uniqueId
        if (closed.get() || !enabled() || !player.hasPermission(SectionWandHandler.PERMISSION) || !held(player)) {
            invalidate(player); return
        }
        if (leftClick || player.isSneaking) {
            invalidate(player); player.sendMessage(Component.text(WandI18n.format(locale, "measure.clear"))); return
        }
        val world = player.world
        val session = sessions.compute(id) { _, previous ->
            if (previous != null && previous.active.get() && previous.viewer === player && previous.world === world) previous else {
                previous?.active?.set(false); Session(player, world, world.uid)
            }
        }!!
        if (!session.busy.compareAndSet(false, true)) return
        fun active() = !closed.get() && session.active.get() && sessions[id] === session
        fun ownerCurrent() = active() && enabled() && player.isOnline && player.world === world &&
            player.hasPermission(SectionWandHandler.PERMISSION) && held(player)
        fun dispatch(action: () -> Unit) = owner(player, {
            try {
                if (ownerCurrent()) try { action() } catch (failure: Exception) {
                    invalidate(id, session)
                    player.sendMessage(Component.text(WandI18n.format(locale, "measure.failed", failure.message?.take(256))))
                } else invalidate(id, session)
            } finally { session.busy.set(false) }
        }, { session.busy.set(false); invalidate(id, session) })
        val capture = try { backend.capture(session.worldId) } catch (failure: Exception) {
            session.busy.set(false); invalidate(id, session)
            player.sendMessage(Component.text(WandI18n.format(locale, "measure.failed", failure.message?.take(256)))); return
        }
        val firstCapture = session.capture
        val first = session.first
        try {
            target(player).whenComplete { selected, failure ->
                owner(player, {
                    if (!ownerCurrent()) { invalidate(id, session); session.busy.set(false); return@owner }
                    if (failure != null || selected == null || selected.isEmpty) {
                        session.busy.set(false); player.sendMessage(Component.text(WandI18n.format(locale, "rail.none"))); return@owner
                    }
                    val railId = selected.get().rail().id
                    val ab = selected.get().arcLength()
                    try {
                        worker {
                            if (!active()) { session.busy.set(false); return@worker }
                            try {
                                require(backend.current(capture) && (firstCapture == null || backend.current(firstCapture))) {
                                    WandI18n.format(locale, "measure.expired")
                                }
                                val point = backend.select(capture, railId, ab)
                                val result = first?.let { backend.measure(capture, it, point) }
                                dispatch {
                                    if (!backend.current(capture)) {
                                        invalidate(id, session)
                                        player.sendMessage(Component.text(WandI18n.format(locale, "measure.expired")))
                                    } else if (result == null) {
                                        session.capture = capture; session.first = point
                                        player.sendMessage(Component.text(WandI18n.format(locale, "measure.first")))
                                    } else {
                                        invalidate(id, session)
                                        player.sendMessage(Component.text(format(result, locale)))
                                    }
                                }
                            } catch (failure: Exception) {
                                val message = failure.message?.take(256) ?: failure.javaClass.simpleName
                                dispatch { invalidate(id, session); player.sendMessage(Component.text(WandI18n.format(locale, "measure.failed", message))) }
                            }
                        }
                    } catch (failure: Exception) {
                        session.busy.set(false); invalidate(id, session)
                        player.sendMessage(Component.text(WandI18n.format(locale, "measure.failed", failure.message?.take(256))))
                    }
                }, { session.busy.set(false); invalidate(id, session) })
            }
        } catch (failure: Exception) {
            session.busy.set(false); invalidate(id, session)
            player.sendMessage(Component.text(WandI18n.format(locale, "rail.failed", failure.message?.take(256))))
        }
    }

    private fun invalidate(id: UUID) { sessions.remove(id)?.active?.set(false) }
    private fun invalidate(player: Player) { sessions[player.uniqueId]?.takeIf { it.viewer === player }?.let { invalidate(player.uniqueId, it) } }
    private fun invalidate(id: UUID, session: Session) { if (sessions.remove(id, session)) session.active.set(false) }
    @EventHandler fun onQuit(event: PlayerQuitEvent) = invalidate(event.player)
    @EventHandler fun onWorld(event: PlayerChangedWorldEvent) = invalidate(event.player)
    @EventHandler(ignoreCancelled = true) fun onHeld(event: PlayerItemHeldEvent) = invalidate(event.player)
    @EventHandler(ignoreCancelled = true) fun onSwap(event: PlayerSwapHandItemsEvent) = invalidate(event.player)
    @EventHandler(ignoreCancelled = true) fun onDrop(event: PlayerDropItemEvent) = invalidate(event.player)
    @EventHandler(ignoreCancelled = true) fun onInventory(event: InventoryClickEvent) { (event.whoClicked as? Player)?.let(::invalidate) }
    @EventHandler(ignoreCancelled = true) fun onDrag(event: InventoryDragEvent) { (event.whoClicked as? Player)?.let(::invalidate) }
    @EventHandler(ignoreCancelled = true) fun onUnload(event: WorldUnloadEvent) {
        sessions.forEach { (id, session) -> if (session.world === event.world) invalidate(id, session) }
    }
    override fun close() { closed.set(true); sessions.keys.forEach(::invalidate) }

    companion object {
        internal fun format(result: RailMeasurementResult, locale: Locale = Locale.US): String =
            WandI18n.format(locale, "measure.result", result.straightMeters, result.trackMeters?.let { WandI18n.format(locale, "measure.distance", it) } ?: WandI18n.format(locale, "measure.disconnected"))
        private fun key(plugin: Plugin) = NamespacedKey(plugin, "measurement_wand")
        private fun isTool(plugin: Plugin, item: ItemStack) =
            item.itemMeta?.persistentDataContainer?.get(key(plugin), PersistentDataType.STRING) == "measure"
        private fun createItem(plugin: Plugin, locale: java.util.Locale) = ItemStack(Material.BLAZE_ROD).apply {
            editMeta { meta ->
                meta.displayName(Component.text(WandI18n.format(locale, "measure.name")))
                meta.persistentDataContainer.set(key(plugin), PersistentDataType.STRING, "measure")
                meta.lore(listOf(Component.text(WandI18n.format(locale, "measure.lore"))))
            }
        }
    }
}
