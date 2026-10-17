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
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import work.art1st.javarailways.bukkit.api.rail.RailTarget
import work.art1st.javarailways.bukkit.api.rail.RailTargetService
import work.art1st.javarailways.simplesignals.region.RailCutPosition
import java.util.Optional
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean

/** Platform capture and entity replies only; searches and definition transactions run on workers. */
internal class SectionWandHandler(
    private val plugin: Plugin,
    private val backend: SectionWandBackend,
    private val targets: RailTargetService,
    private val enabled: () -> Boolean = { plugin.isEnabled },
    private val worker: (() -> Unit) -> Unit = { action -> plugin.server.asyncScheduler.runNow(plugin) { action() } },
    private val entity: (Player, () -> Unit) -> Unit = { player, action -> player.scheduler.run(plugin, { action() }, {}) },
    private val stateInHand: (Player) -> SectionWandState? = { player -> readState(plugin, player.inventory.itemInMainHand) },
    private val saveState: (Player, SectionWandState) -> Unit = { player, state ->
        applyState(plugin, player.inventory.itemInMainHand, state, player.locale())
    },
    private val giveTool: (Player, SectionWandTool) -> Unit = { player, tool ->
        require(player.inventory.firstEmpty() >= 0) { WandI18n.format(player, "inventory.full") }
        player.inventory.addItem(createItem(plugin, tool, player.locale()))
    },
    private val findTarget: (Player) -> CompletionStage<Optional<RailTarget>> = { player -> targets.findLookedAtRail(player, 64.0, 0.75) },
    private val repeatDisplay: (Player, () -> Unit, () -> Unit) -> AutoCloseable = { player, action, retired ->
        val task = player.scheduler.runAtFixedRate(plugin, { action() }, retired, 1L, RoutePreviewDrawing.REFRESH_TICKS)
        AutoCloseable { task?.cancel() }
    },
    private val render: (Player, RoutePreviewDrawing, Int) -> Unit = { player, drawing, offset -> drawing.draw(player, offset) },
    private val checkOverlay: (Player) -> Unit = {},
    private val showDialog: (Player, String, List<Pair<String, () -> Unit>>) -> Unit = WandDialogs::show,
    private val showName: (Player, String, String, (String?) -> Unit) -> Unit = WandDialogs::boundaryName
) : Listener, CommandExecutor {
    private val sessions = SectionWandSessions()

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!enabled()) return true
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(Component.text(WandI18n.format(sender, "permission", PERMISSION))); return true
        }
        if (sender !is Player) { sender.sendMessage(Component.text(WandI18n.format(sender, "section.player"))); return true }
        val tool = if (args.size in 2..3 && args[0] == "wand" && args[1] == "section")
            SectionWandTool.entries.singleOrNull { it.key == (args.getOrNull(2) ?: "create") } else null
        if (tool == null) { sender.sendMessage(Component.text(USAGE)); return true }
        sessions.invalidate(sender.uniqueId)
        try {
            giveTool(sender, tool)
            sender.sendMessage(Component.text(when (tool) {
                SectionWandTool.CREATE -> WandI18n.format(sender, "section.create")
                SectionWandTool.DELETE -> WandI18n.format(sender, "section.delete")
            }))
        } catch (failure: Exception) { sender.sendMessage(Component.text(failure.message ?: WandI18n.format(sender, "section.create.failed"))) }
        checkOverlay(sender)
        return true
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || event.action == Action.PHYSICAL) return
        val player = event.player
        val tool = stateInHand(player)?.tool ?: return
        checkOverlay(player)
        event.isCancelled = true
        click(player, tool, event.action == Action.LEFT_CLICK_AIR || event.action == Action.LEFT_CLICK_BLOCK)
    }

    internal fun click(player: Player, tool: SectionWandTool, leftClick: Boolean) {
        val state = stateInHand(player)?.takeIf { it.tool == tool } ?: return
        val locale = player.locale()
        val playerId = player.uniqueId
        if (!enabled() || !player.hasPermission(PERMISSION)) {
            sessions.invalidate(playerId)
            if (enabled()) player.sendMessage(Component.text(WandI18n.format(locale, "permission", PERMISSION)))
            return
        }
        if (player.isSneaking && !leftClick) { openSettings(player, state); return }
        if (player.isSneaking) {
            sessions.invalidate(playerId); player.sendMessage(Component.text(WandI18n.format(locale, "section.clear"))); return
        }
        val worldId = player.world.uid
        val session = sessions.begin(playerId, worldId, state, true) ?: return
        val captured = try { backend.capture(worldId) } catch (_: Exception) { null }
        if (captured == null) {
            session.busy.set(false); player.sendMessage(Component.text(WandI18n.format(locale, "world.unavailable"))); return
        }
        fun active(): Boolean = sessions.current(playerId, session) && enabled()
        fun ownerCurrent(): Boolean = active() && player.isOnline && player.world.uid == worldId &&
            player.hasPermission(PERMISSION) && stateInHand(player) == state
        fun reply(message: String) = entity(player) {
            if (ownerCurrent()) player.sendMessage(Component.text(message.take(8192)))
        }
        try {
            findTarget(player).whenComplete { target, failure ->
                entity(player) {
                    if (!ownerCurrent()) { sessions.invalidate(playerId, session); return@entity }
                    if (failure != null || target == null || target.isEmpty) {
                        session.busy.set(false)
                        player.sendMessage(Component.text(WandI18n.format(locale, "rail.none.detail", failure?.message?.let { ": ${it.take(256)}" }.orEmpty())))
                        return@entity
                    }
                    val selected = target.get()
                    worker {
                        if (!active()) return@worker
                        try {
                            if (!backend.current(captured)) {
                                entity(player) {
                                    if (ownerCurrent()) {
                                        player.sendMessage(Component.text(WandI18n.format(locale, "selection.expired")))
                                    }
                                    session.busy.set(false)
                                }
                                return@worker
                            }
                            val point = RailCutPosition(selected.rail().id, selected.arcLength())
                            if (session.naming) {
                                val naming = backend.naming(captured, point)
                                entity(player) {
                                    session.busy.set(false)
                                    if (ownerCurrent()) {
                                        if (naming == null) player.sendMessage(Component.text(WandI18n.format(locale, "boundary.select")))
                                        else { session.naming = false; editName(player, state, session, naming) }
                                    }
                                }
                                return@worker
                            }
                            if (tool == SectionWandTool.DELETE && !leftClick) {
                                val deletion = backend.prepareDeletion(captured, point, state.retainDevices)
                                if (deletion != null) {
                                    entity(player) {
                                        session.busy.set(false)
                                        if (ownerCurrent()) confirmDeletion(player, state, session, deletion)
                                    }
                                    return@worker
                                }
                            }
                            val result = backend.perform(captured, tool, leftClick, point, state.retainDevices, ::active)
                            val warnings = RoutePreviewDrawing.warnings(result.warnings) { id -> targets.findCurve(worldId, id).orElse(null) }
                            entity(player) {
                                if (ownerCurrent()) {
                                    session.kind = result.kind
                                    player.sendMessage(Component.text(result.text.render(player.locale()).take(8192)))
                                    showStatus(player)
                                    session.refresh?.close(); session.refresh = null
                                    if (warnings.points.isNotEmpty()) {
                                        var offset = 0
                                        session.refresh = repeatDisplay(player, {
                                            // An edit in this same session may have committed before its owner
                                            // reply attaches the next drawing. Do not cancel that successful reply.
                                            if (!ownerCurrent() || (!session.busy.get() && result.warningCapture?.let { !backend.current(it) } == true))
                                                sessions.invalidate(playerId, session)
                                            else {
                                                render(player, warnings, offset)
                                                offset = warnings.nextOffset(offset)
                                            }
                                        }, { sessions.invalidate(playerId, session) })
                                        if (!active()) session.close()
                                    }
                                }
                                session.busy.set(false)
                            }
                        } catch (failure: Exception) {
                            reply("SimpleSignals: ${failure.message ?: failure.javaClass.simpleName}")
                            session.busy.set(false)
                        }
                    }
                }
            }
        } catch (failure: Exception) {
            session.busy.set(false); player.sendMessage(Component.text(WandI18n.format(locale, "rail.failed", failure.message?.take(256))))
        }
    }

    internal fun openSettings(player: Player, expected: SectionWandState) {
        val playerId = player.uniqueId
        if (!enabled() || !player.hasPermission(PERMISSION) || stateInHand(player) != expected) return
        sessions.invalidate(playerId)
        val worldId = player.world.uid
        val session = sessions.begin(playerId, worldId, expected, true) ?: return
        session.busy.set(false)
        val token = Any()
        session.dialogToken = token
        val used = AtomicBoolean(false)
        val locale = player.locale()
        showDialog(player, WandI18n.format(locale, "section.settings"), listOf(
            WandI18n.format(locale, "section.setting.retain", WandI18n.format(locale,
                if (expected.retainDevices) "section.retain.on" else "section.retain.off")) to {
                entity(player) {
                    if (sessions.current(playerId, session) && session.dialogToken === token && enabled() && player.isOnline && player.world.uid == worldId &&
                        player.hasPermission(PERMISSION) && stateInHand(player) == expected && used.compareAndSet(false, true)) {
                        sessions.invalidate(playerId, session)
                        saveState(player, expected.changed(retainDevices = !expected.retainDevices))
                        showStatus(player)
                    }
                }
            },
            WandI18n.format(locale, "boundary.edit") to { entity(player) {
                if (sessions.current(playerId, session) && session.dialogToken === token && enabled() && player.isOnline &&
                    player.world.uid == worldId && player.hasPermission(PERMISSION) && stateInHand(player) == expected && used.compareAndSet(false, true)) {
                    session.dialogToken = null; session.naming = true
                    player.sendMessage(Component.text(WandI18n.format(locale, "boundary.select")))
                }
            } }, WandI18n.format(locale, "cancel") to { entity(player) {
                if (session.dialogToken === token) sessions.invalidate(playerId, session)
            } }))
    }

    private fun editName(player: Player, state: SectionWandState, session: SectionWandSessions.Session,
        selection: SectionBoundaryNaming) {
        val playerId = player.uniqueId
        val token = Any()
        session.dialogToken = token
        val used = AtomicBoolean(false)
        fun current() = sessions.current(playerId, session) && enabled() && session.dialogToken === token
        fun owner() = current() && player.isOnline && player.world.uid == session.worldId &&
            player.hasPermission(PERMISSION) && stateInHand(player) == state
        showName(player, selection.description.render(player.locale()), selection.initial) { input -> entity(player) {
            if (!owner() || !used.compareAndSet(false, true)) return@entity
            if (input == null) { sessions.invalidate(playerId, session); return@entity }
            try { work.art1st.javarailways.simplesignals.persistence.normalizeBoundaryName(input) }
            catch (failure: IllegalArgumentException) {
                player.sendMessage(Component.text(failure.message.orEmpty()))
                editName(player, state, session, selection)
                return@entity
            }
            session.busy.set(true)
            worker {
                try {
                    if (!current()) return@worker
                    val result = backend.rename(selection, input, ::current)
                    entity(player) {
                        if (owner()) player.sendMessage(Component.text(result.text.render(player.locale())))
                        sessions.invalidate(playerId, session)
                    }
                } catch (failure: Exception) { entity(player) {
                    if (owner()) player.sendMessage(Component.text(failure.message.orEmpty()))
                    sessions.invalidate(playerId, session)
                } }
            }
        } }
    }

    private fun confirmDeletion(player: Player, state: SectionWandState, session: SectionWandSessions.Session,
        selection: SectionWandDeletion) {
        val locale = player.locale()
        val playerId = player.uniqueId
        val token = Any()
        session.dialogToken = token
        val used = AtomicBoolean(false)
        fun active() = sessions.current(playerId, session) && enabled() && session.dialogToken === token
        fun ownerCurrent() = active() && player.isOnline && player.world.uid == session.worldId &&
            player.hasPermission(PERMISSION) && stateInHand(player) == state
        fun complete(confirm: Boolean) = entity(player) {
            if (!ownerCurrent() || !used.compareAndSet(false, true)) return@entity
            if (!confirm) {
                sessions.invalidate(playerId, session)
                player.sendMessage(Component.text(WandI18n.format(locale, "section.delete.cancelled")))
                return@entity
            }
            if (!session.busy.compareAndSet(false, true)) return@entity
            try { worker {
                try {
                    if (!active()) return@worker
                    val result = backend.delete(selection, ::active)
                    entity(player) {
                        if (ownerCurrent()) {
                            session.kind = result.kind
                            player.sendMessage(Component.text(result.text.render(player.locale()).take(8192)))
                            showStatus(player)
                        }
                        sessions.invalidate(playerId, session)
                    }
                } catch (failure: Exception) {
                    entity(player) {
                        if (ownerCurrent()) player.sendMessage(Component.text("SimpleSignals: ${failure.message ?: failure.javaClass.simpleName}"))
                        sessions.invalidate(playerId, session)
                    }
                }
            } } catch (failure: Exception) {
                sessions.invalidate(playerId, session)
                player.sendMessage(Component.text(WandI18n.format(locale, "rail.failed", failure.message)))
            }
        }
        showDialog(player, WandI18n.format(locale, "section.delete.confirm", selection.text,
            WandI18n.format(locale, if (selection.retainDevices) "section.retain.on" else "section.retain.off")), listOf(
            WandI18n.format(locale, "section.delete.accept") to { complete(true) },
            WandI18n.format(locale, "cancel") to { complete(false) }))
    }

    internal fun switchMode(player: Player) {
        sessions.invalidate(player.uniqueId)
        if (!enabled() || !player.hasPermission(PERMISSION)) return
        val state = stateInHand(player) ?: return
        val next = if (state.tool == SectionWandTool.CREATE) SectionWandTool.DELETE else SectionWandTool.CREATE
        saveState(player, state.changed(tool = next))
        showStatus(player)
        checkOverlay(player)
    }

    @EventHandler fun onQuit(event: PlayerQuitEvent) { sessions.invalidate(event.player.uniqueId) }
    @EventHandler fun onWorldChange(event: PlayerChangedWorldEvent) { sessions.invalidate(event.player.uniqueId) }
    @EventHandler(ignoreCancelled = true) fun onHeldItem(event: PlayerItemHeldEvent) { sessions.invalidate(event.player.uniqueId) }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSwapHands(event: PlayerSwapHandItemsEvent) {
        work.art1st.javarailways.bukkit.api.wand.WandModeSwitch.handle(event,
            { readTool(plugin, it) != null }, ::switchMode)
    }
    @EventHandler(ignoreCancelled = true) fun onInventoryClick(event: InventoryClickEvent) { sessions.invalidate(event.whoClicked.uniqueId) }
    @EventHandler(ignoreCancelled = true) fun onDrop(event: PlayerDropItemEvent) { sessions.invalidate(event.player.uniqueId) }
    @EventHandler(ignoreCancelled = true) fun onWorldUnload(event: WorldUnloadEvent) { sessions.invalidateWorld(event.world.uid) }
    fun close() = sessions.close()

    /** Called by the existing editor overlay refresh on the player owner; no graph or world query. */
    fun showStatus(player: Player) {
        val state = stateInHand(player) ?: return
        val locale = player.locale()
        player.sendActionBar(Component.text(WandI18n.format(locale, "section.status",
            WandI18n.format(locale, "section.mode.${state.tool.name}"),
            WandI18n.format(locale, "section.kind.${sessions.kind(player.uniqueId).name}"),
            WandI18n.format(locale, if (state.retainDevices) "section.retain.on" else "section.retain.off"))))
    }

    companion object {
        const val PERMISSION = "simplesignals.admin"
        const val USAGE = "/simplesignals wand section [create|delete], or /simplesignals wand signal|route|measure"
        private fun key(plugin: Plugin) = NamespacedKey(plugin, "section_wand")
        internal fun readTool(plugin: Plugin, stack: ItemStack): SectionWandTool? {
            return readState(plugin, stack)?.tool
        }
        internal fun readState(plugin: Plugin, stack: ItemStack): SectionWandState? {
            val value = stack.itemMeta?.persistentDataContainer?.get(key(plugin), PersistentDataType.STRING) ?: return null
            return try { SectionWandStateCodec.decode(value) } catch (_: IllegalArgumentException) { null }
        }
        private fun createItem(plugin: Plugin, tool: SectionWandTool, locale: java.util.Locale): ItemStack = ItemStack(Material.BLAZE_ROD).apply {
            applyState(plugin, this, SectionWandState.create(tool), locale)
        }
        private fun applyState(plugin: Plugin, stack: ItemStack, state: SectionWandState, locale: java.util.Locale) {
            stack.editMeta { meta ->
                meta.displayName(Component.text(WandI18n.format(locale, "section.name.${state.tool.name}")))
                meta.persistentDataContainer.set(key(plugin), PersistentDataType.STRING, SectionWandStateCodec.encode(state))
                meta.lore(listOf(Component.text(WandI18n.format(locale, "section.lore"))))
            }
        }
    }
}
