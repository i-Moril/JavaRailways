package work.art1st.javarailways.simplesignals.authoring

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.format.NamedTextColor
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
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import work.art1st.javarailways.bukkit.api.rail.RailTarget
import work.art1st.javarailways.bukkit.api.rail.RailTargetService
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simplesignals.region.SIMPLE_SIGNALS_NAMESPACED_ID
import work.art1st.javarailways.simplesignals.signalpack.AvailableSignalType
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Fixed-block primary signal updater with item-bound settings and Folia owner scheduling. */
internal class SignalWandHandler(
    private val plugin: Plugin,
    private val backend: SignalWandBackend,
    private val targets: RailTargetService,
    private val enabled: () -> Boolean = { plugin.isEnabled },
    private val worker: (() -> Unit) -> Unit = { action -> plugin.server.asyncScheduler.runNow(plugin) { action() } },
    private val entity: (Player, () -> Unit) -> Unit = { player, action -> player.scheduler.run(plugin, { action() }, {}) },
    private val toolInHand: (Player) -> SignalWandState? = { player -> readTool(plugin, player.inventory.itemInMainHand) },
    private val giveTool: (Player, SignalWandState) -> Unit = { player, state ->
        require(player.inventory.firstEmpty() >= 0) { "Make one empty inventory slot for the wand" }
        player.inventory.addItem(createItem(plugin, state))
    },
    private val replaceToolState: (Player, SignalWandState, SignalWandState) -> Boolean =
        { player, expected, replacement -> replaceMainHandState(plugin, player, expected, replacement) },
    private val showSettings: (Player, List<SignalWandSetting>, (String) -> Unit) -> Unit =
        { player, settings, selected -> showNativeSettings(player, settings, selected) },
    private val scheduleStatus: (Player, () -> Unit, () -> Unit) -> SignalWandStatusTask? =
        { player, action, retired ->
            player.scheduler.runAtFixedRate(plugin, { action() }, retired, 1L, STATUS_PERIOD_TICKS)
                ?.let(::PaperSignalWandStatusTask)
        },
    private val newItemId: () -> UUID = UUID::randomUUID,
    private val findTarget: (Player) -> CompletionStage<Optional<RailTarget>> =
        { player -> targets.findLookedAtRail(player, 64.0, 0.75) }
) : Listener, CommandExecutor {
    private val sessions = SignalWandSessions()
    private val statusTasks = ConcurrentHashMap<UUID, SignalWandStatusRegistration>()
    private val closed = AtomicBoolean(false)

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!operational()) return true
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(Component.text("SimpleSignals: missing permission $PERMISSION")); return true
        }
        if (sender !is Player) {
            sender.sendMessage(Component.text("Signal wands require a player")); return true
        }
        if (args.size != 2 || args[0] != "wand" || args[1] != "signal") {
            sender.sendMessage(Component.text(USAGE)); return true
        }
        val available = currentTypes()
        if (available == null) {
            sender.sendMessage(Component.text("Signal type catalog is not ready")); return true
        }
        if (available.isEmpty()) {
            sender.sendMessage(Component.text("No signal types are available")); return true
        }
        val state = SignalWandState(newItemId(), 0L, available.first().signalType)
        sessions.invalidate(sender.uniqueId)
        try {
            giveTool(sender, state)
            refreshStatus(sender)
            sender.sendMessage(Component.text(
                "Signal Wand created. Shift-right-click to select a signal type; click an existing fixed-block entrance signal to update it."
            ))
        } catch (failure: Exception) {
            sender.sendMessage(Component.text(failure.message ?: "Cannot create signal wand"))
        }
        return true
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || event.action == Action.PHYSICAL) return
        val player = event.player
        val state = toolInHand(player) ?: return
        event.isCancelled = true
        refreshStatus(player)
        if (player.isSneaking && (event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK)) {
            openSettings(player, state)
        } else {
            click(player, state)
        }
    }

    internal fun openSettings(player: Player, openingState: SignalWandState) {
        if (!canUse(player) || toolInHand(player) != openingState) return
        val available = currentTypes()
        if (available == null) {
            player.sendMessage(Component.text("Signal type catalog is not ready")); return
        }
        if (available.isEmpty()) {
            player.sendMessage(Component.text("No signal types are available")); return
        }
        val settings = available.map {
            SignalWandSetting(it.signalType, it.displayName, it.signalType == openingState.signalType)
        }
        showSettings(player, settings) { signalType ->
            entity(player) { selectType(player, openingState, signalType) }
        }
    }

    internal fun selectType(player: Player, openingState: SignalWandState, signalType: String) {
        if (!canUse(player)) return
        val current = toolInHand(player)
        if (current != openingState) {
            player.sendMessage(Component.text("Signal Wand settings expired; reopen the settings page")); return
        }
        val available = currentTypes()
        if (available == null || available.none { it.signalType == signalType }) {
            player.sendMessage(Component.text("Signal type is no longer available: $signalType")); return
        }
        val revision = try {
            Math.addExact(current.stateRevision, 1L)
        } catch (_: ArithmeticException) {
            player.sendMessage(Component.text("Signal Wand state revision overflow; replace this wand")); return
        }
        val replacement = SignalWandState(current.itemId, revision, signalType)
        if (!replaceToolState(player, current, replacement)) {
            player.sendMessage(Component.text("Signal Wand settings expired; reopen the settings page")); return
        }
        sessions.invalidate(player.uniqueId)
        refreshStatus(player)
        player.sendMessage(Component.text("Signal Wand type selected: $signalType"))
    }

    internal fun click(player: Player, state: SignalWandState) {
        val playerId = player.uniqueId
        if (!canUse(player) || toolInHand(player) != state) {
            sessions.invalidate(playerId)
            if (operational() && !player.hasPermission(PERMISSION)) {
                player.sendMessage(Component.text("SimpleSignals: missing permission $PERMISSION"))
            }
            return
        }
        val worldId = player.world.uid
        val session = sessions.begin(playerId, worldId, state) ?: return
        val captured = try {
            backend.capture(worldId, state.signalType)
        } catch (failure: Exception) {
            session.busy.set(false)
            player.sendMessage(Component.text("SimpleSignals: ${failure.message ?: failure.javaClass.simpleName}"))
            return
        }
        val sight = player.eyeLocation.direction
        fun active(): Boolean = sessions.current(playerId, session) && operational()
        fun ownerCurrent(): Boolean = active() && player.isOnline && player.world.uid == worldId &&
            player.hasPermission(PERMISSION) && toolInHand(player) == state
        fun complete(message: String) = entity(player) {
            if (ownerCurrent()) player.sendMessage(Component.text(message.take(MESSAGE_LIMIT)))
            session.busy.set(false)
        }
        try {
            findTarget(player).whenComplete { target, failure ->
                entity(player) {
                    if (!ownerCurrent()) { sessions.invalidate(playerId, session); return@entity }
                    if (failure != null || target == null || target.isEmpty) {
                        session.busy.set(false)
                        player.sendMessage(Component.text("No rail selected${failure?.message?.let { ": ${it.take(256)}" }.orEmpty()}"))
                        return@entity
                    }
                    val selected = target.get()
                    worker {
                        if (!active()) return@worker
                        try {
                            require(backend.current(captured)) { "Signal selection expired; select again" }
                            val curve: Curve = targets.findCurve(worldId, selected.rail().id)
                                .orElseThrow { IllegalArgumentException("Rail no longer exists") }
                            val tangent = curve.getPointAt(selected.arcLength()).tangent
                            val dot = tangent.x() * sight.x + tangent.y() * sight.y + tangent.z() * sight.z
                            require(abs(dot) >= 0.15) { "Look along the rail into the fixed block" }
                            val point = RailPositionSnapshot(selected.rail().id,
                                if (dot >= 0) ViewDirection.AB else ViewDirection.BA, selected.arcLength())
                            val outcome = backend.perform(captured, point, ::active)
                            complete(outcome.message)
                        } catch (failure: Exception) {
                            complete("SimpleSignals: ${failure.message ?: failure.javaClass.simpleName}")
                        }
                    }
                }
            }
        } catch (failure: Exception) {
            session.busy.set(false)
            player.sendMessage(Component.text("Rail selection failed: ${failure.message?.take(256)}"))
        }
    }

    internal fun refreshStatus(player: Player) {
        val playerId = player.uniqueId
        val state = if (operational() && player.isOnline && player.hasPermission(PERMISSION)) toolInHand(player) else null
        if (state == null) {
            stopStatus(playerId)
            return
        }
        ensureStatus(player)
        player.sendActionBar(statusText(state, backend.availableSignalTypes()))
    }

    private fun ensureStatus(player: Player) {
        if (closed.get()) return
        val playerId = player.uniqueId
        val registration = SignalWandStatusRegistration()
        if (statusTasks.putIfAbsent(playerId, registration) != null) return
        if (closed.get()) {
            statusTasks.remove(playerId, registration)
            registration.cancel()
            return
        }
        val task = scheduleStatus(
            player,
            { refreshStatus(player) },
            { statusTasks.remove(playerId, registration); registration.retire() }
        )
        if (task == null) {
            statusTasks.remove(playerId, registration)
        } else {
            registration.attach(task)
        }
    }

    private fun stopStatus(playerId: UUID) = statusTasks.remove(playerId)?.cancel()
    private fun deferRefresh(player: Player) = entity(player) { refreshStatus(player) }
    private fun currentTypes(): List<AvailableSignalType>? =
        backend.availableSignalTypes()?.sortedBy(AvailableSignalType::signalType)
    private fun operational(): Boolean = !closed.get() && enabled()
    private fun canUse(player: Player): Boolean = operational() && player.hasPermission(PERMISSION)

    @EventHandler fun onJoin(event: PlayerJoinEvent) = deferRefresh(event.player)
    @EventHandler fun onQuit(event: PlayerQuitEvent) {
        sessions.invalidate(event.player.uniqueId)
        stopStatus(event.player.uniqueId)
    }
    @EventHandler fun onWorldChange(event: PlayerChangedWorldEvent) {
        sessions.invalidate(event.player.uniqueId)
        deferRefresh(event.player)
    }
    @EventHandler(ignoreCancelled = true) fun onHeldItem(event: PlayerItemHeldEvent) {
        sessions.invalidate(event.player.uniqueId)
        deferRefresh(event.player)
    }
    @EventHandler(ignoreCancelled = true) fun onSwapHands(event: PlayerSwapHandItemsEvent) {
        sessions.invalidate(event.player.uniqueId)
        deferRefresh(event.player)
    }
    @EventHandler(ignoreCancelled = true) fun onInventoryClick(event: InventoryClickEvent) {
        sessions.invalidate(event.whoClicked.uniqueId)
        (event.whoClicked as? Player)?.let(::deferRefresh)
    }
    @EventHandler(ignoreCancelled = true) fun onInventoryDrag(event: InventoryDragEvent) {
        sessions.invalidate(event.whoClicked.uniqueId)
        (event.whoClicked as? Player)?.let(::deferRefresh)
    }
    @EventHandler(ignoreCancelled = true) fun onDrop(event: PlayerDropItemEvent) {
        sessions.invalidate(event.player.uniqueId)
        deferRefresh(event.player)
    }
    @EventHandler(ignoreCancelled = true) fun onPickup(event: EntityPickupItemEvent) {
        (event.entity as? Player)?.let(::deferRefresh)
    }
    @EventHandler(ignoreCancelled = true) fun onWorldUnload(event: WorldUnloadEvent) =
        sessions.invalidateWorld(event.world.uid)

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        sessions.close()
        statusTasks.values.forEach(SignalWandStatusRegistration::cancel)
        statusTasks.clear()
    }

    companion object {
        const val PERMISSION = "simplesignals.admin"
        const val USAGE = "/simplesignals wand signal"
        const val MESSAGE_LIMIT = 8192
        const val STATUS_PERIOD_TICKS = 10L
        private val STATE_JSON = Json { encodeDefaults = true; explicitNulls = false }
        private fun markerKey(plugin: Plugin) = NamespacedKey(plugin, "signal_wand")
        private fun stateKey(plugin: Plugin) = NamespacedKey(plugin, "signal_wand_state")

        internal fun readTool(plugin: Plugin, stack: ItemStack): SignalWandState? =
            stack.itemMeta?.persistentDataContainer?.let { readTool(plugin, it) }

        internal fun readTool(plugin: Plugin, data: PersistentDataContainer): SignalWandState? {
            if (data.get(markerKey(plugin), PersistentDataType.BYTE) != MARKER_VALUE) return null
            val encoded = data.get(stateKey(plugin), PersistentDataType.STRING) ?: return null
            return decodeState(encoded)
        }

        internal fun writeTool(plugin: Plugin, data: PersistentDataContainer, state: SignalWandState) {
            data.set(markerKey(plugin), PersistentDataType.BYTE, MARKER_VALUE)
            data.set(stateKey(plugin), PersistentDataType.STRING, encodeState(state))
        }

        internal fun encodeState(state: SignalWandState): String = STATE_JSON.encodeToString(
            PersistedSignalWandState(state.itemId.toString(), state.stateRevision, state.signalType)
        )

        internal fun decodeState(encoded: String): SignalWandState? = try {
            val persisted = STATE_JSON.decodeFromString<PersistedSignalWandState>(encoded)
            SignalWandState(UUID.fromString(persisted.itemId), persisted.stateRevision, persisted.signalType)
        } catch (_: Exception) {
            null
        }

        internal fun createItem(plugin: Plugin, state: SignalWandState): ItemStack = ItemStack(Material.BLAZE_ROD).apply {
            editMeta { meta ->
                meta.displayName(Component.text("SimpleSignals Signal Wand"))
                writeTool(plugin, meta.persistentDataContainer, state)
                meta.lore(listOf(
                    Component.text("Shift-right-click: select signal type"),
                    Component.text("Selected type: ${state.signalType}")
                ))
            }
        }

        internal fun replaceMainHandState(
            plugin: Plugin,
            player: Player,
            expected: SignalWandState,
            replacement: SignalWandState
        ): Boolean {
            val item = player.inventory.itemInMainHand
            if (readTool(plugin, item) != expected) return false
            item.editMeta { meta ->
                writeTool(plugin, meta.persistentDataContainer, replacement)
                meta.lore(listOf(
                    Component.text("Shift-right-click: select signal type"),
                    Component.text("Selected type: ${replacement.signalType}")
                ))
            }
            return readTool(plugin, item) == replacement
        }

        internal fun statusText(state: SignalWandState, available: List<AvailableSignalType>?): Component {
            val availableNow = available?.any { it.signalType == state.signalType } == true
            val suffix = when {
                available == null -> " (catalog unavailable)"
                !availableNow -> " (unavailable)"
                else -> ""
            }
            return Component.text("Signal Wand | mode: fixed-block signal | type: ${state.signalType}$suffix")
        }

        private fun showNativeSettings(
            player: Player,
            settings: List<SignalWandSetting>,
            selected: (String) -> Unit
        ) {
            val buttons = settings.map { setting ->
                val label = (if (setting.selected) "✓ " else "") +
                    "${setting.signalType} — ${setting.displayName.take(DISPLAY_NAME_LIMIT)}"
                val action = DialogAction.customClick(
                    { _, audience -> if (audience === player) selected(setting.signalType) },
                    ClickCallback.Options.builder().uses(1).lifetime(ClickCallback.DEFAULT_LIFETIME).build()
                )
                ActionButton.builder(Component.text(label, if (setting.selected) NamedTextColor.GREEN else NamedTextColor.YELLOW))
                    .action(action)
                    .build()
            }
            val dialog = Dialog.create { factory ->
                factory.empty()
                    .base(DialogBase.builder(Component.text("Signal Wand settings", NamedTextColor.GOLD))
                        .body(listOf(DialogBody.plainMessage(Component.text(
                            "Select the signal type stored by this Wand.", NamedTextColor.GRAY))))
                        .canCloseWithEscape(true)
                        .build())
                    .type(DialogType.multiAction(buttons, null, 1))
            }
            player.showDialog(dialog)
        }

        private const val DISPLAY_NAME_LIMIT = 64
        private const val MARKER_VALUE: Byte = 1
    }
}

internal data class SignalWandState(
    val itemId: UUID,
    val stateRevision: Long,
    val signalType: String
) {
    init {
        require(stateRevision >= 0L) { "stateRevision must be non-negative" }
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(signalType)) { "signalType must be namespaced" }
    }
}

@Serializable
private data class PersistedSignalWandState(
    val itemId: String,
    val stateRevision: Long,
    val signalType: String
)

internal data class SignalWandSetting(
    val signalType: String,
    val displayName: String,
    val selected: Boolean
)

internal fun interface SignalWandStatusTask {
    fun cancel()
}

private class SignalWandStatusRegistration {
    private val stopped = AtomicBoolean(false)
    @Volatile private var task: SignalWandStatusTask? = null

    fun attach(task: SignalWandStatusTask) {
        this.task = task
        if (stopped.get()) task.cancel()
    }

    fun cancel() {
        if (stopped.compareAndSet(false, true)) task?.cancel()
    }

    fun retire() {
        stopped.set(true)
    }
}

private class PaperSignalWandStatusTask(private val task: ScheduledTask) : SignalWandStatusTask {
    override fun cancel() {
        task.cancel()
    }
}

private class SignalWandSessions {
    class Session(val worldId: UUID, val state: SignalWandState) {
        val active = AtomicBoolean(true)
        val busy = AtomicBoolean(false)
        fun close() = active.set(false)
    }
    private val sessions = ConcurrentHashMap<UUID, Session>()
    private val closed = AtomicBoolean(false)

    fun begin(playerId: UUID, worldId: UUID, state: SignalWandState): Session? {
        if (closed.get()) return null
        val session = sessions.compute(playerId) { _, old ->
            if (old != null && old.active.get() && old.worldId == worldId && old.state == state) old
            else { old?.close(); Session(worldId, state) }
        }!!
        return session.takeIf { !closed.get() && it.busy.compareAndSet(false, true) }
    }
    fun current(playerId: UUID, session: Session): Boolean =
        !closed.get() && session.active.get() && sessions[playerId] === session
    fun invalidate(playerId: UUID) { sessions.remove(playerId)?.close() }
    fun invalidate(playerId: UUID, session: Session) { if (sessions.remove(playerId, session)) session.close() }
    fun invalidateWorld(worldId: UUID) = sessions.entries.forEach { (playerId, session) ->
        if (session.worldId == worldId) invalidate(playerId, session)
    }
    fun close() { closed.set(true); sessions.keys.forEach(::invalidate) }
}
