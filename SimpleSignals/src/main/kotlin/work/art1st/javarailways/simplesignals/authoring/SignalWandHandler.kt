package work.art1st.javarailways.simplesignals.authoring

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
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
import work.art1st.javarailways.simplesignals.signal.FixedBlockEntryDirection
import work.art1st.javarailways.simplesignals.signalpack.AvailableSignalType
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Primary-signal authoring with item-bound settings and Folia owner scheduling. */
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
    private val showSettings: (Player, List<SignalWandSetting>, (SignalWandSettingAction) -> Unit) -> Unit =
        { player, settings, selected -> showNativeSettings(player, settings, selected) },
    private val showChoices: (Player, List<SignalWandChoice>, (SignalWandChoice) -> Unit) -> Unit =
        { player, choices, selected -> showNativeChoices(player, choices, selected) },
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
    private val choiceTokens = ConcurrentHashMap<UUID, Any>()
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
        val state = SignalWandState(newItemId(), 0L, SignalWandMode.PLACEMENT,
            SignalWandControlKind.FIXED_BLOCK, available.first().signalType,
            presentationModelId = backend.availableModelIds()?.firstOrNull())
        sessions.invalidate(sender.uniqueId)
        try {
            giveTool(sender, state)
            refreshStatus(sender)
            sender.sendMessage(Component.text(
                "Signal Wand created. Shift-right-click for mode/type; left-click an entrance to bind, then right-click a rail to place."
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
            click(player, state, event.action)
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
        val settings = SignalWandMode.entries.map { mode ->
            SignalWandSetting(SignalWandSettingAction.Mode(mode), "Mode: ${mode.label}", mode == openingState.mode)
        } + SignalWandControlKind.entries.map { kind ->
            SignalWandSetting(SignalWandSettingAction.ControlKind(kind),
                "Control: ${kind.label}", kind == openingState.controlKind)
        } + available.map {
            SignalWandSetting(SignalWandSettingAction.Type(it.signalType),
                "${it.signalType} — ${it.displayName}", it.signalType == openingState.signalType)
        } + backend.availableModelIds().orEmpty().map { modelId ->
            SignalWandSetting(SignalWandSettingAction.Model(modelId),
                "Presentation model: $modelId", modelId == openingState.presentationModelId)
        }
        showSettings(player, settings) { action ->
            entity(player) { selectSetting(player, openingState, action) }
        }
    }

    internal fun selectSetting(
        player: Player,
        openingState: SignalWandState,
        action: SignalWandSettingAction
    ) {
        if (!canUse(player)) return
        val current = toolInHand(player)
        if (current != openingState) {
            player.sendMessage(Component.text("Signal Wand settings expired; reopen the settings page")); return
        }
        val available = currentTypes()
        if (available == null) {
            player.sendMessage(Component.text("Signal type catalog is not ready")); return
        }
        if (action is SignalWandSettingAction.Type && available.none { it.signalType == action.signalType }) {
            player.sendMessage(Component.text("Signal type is no longer available: ${action.signalType}")); return
        }
        if (action is SignalWandSettingAction.Model &&
            backend.availableModelIds()?.contains(action.modelId) != true) {
            player.sendMessage(Component.text("Presentation model is no longer available: ${action.modelId}")); return
        }
        val revision = try {
            Math.addExact(current.stateRevision, 1L)
        } catch (_: ArithmeticException) {
            player.sendMessage(Component.text("Signal Wand state revision overflow; replace this wand")); return
        }
        val replacement = when (action) {
            is SignalWandSettingAction.Mode -> current.copy(
                stateRevision = revision,
                mode = action.mode,
                binding = if (action.mode == current.mode) current.binding else null
            )
            is SignalWandSettingAction.ControlKind -> current.copy(
                stateRevision = revision,
                controlKind = action.controlKind,
                binding = if (action.controlKind == current.controlKind) current.binding else null
            )
            is SignalWandSettingAction.Type -> current.copy(
                stateRevision = revision,
                signalType = action.signalType
            )
            is SignalWandSettingAction.Model -> current.copy(
                stateRevision = revision,
                presentationModelId = action.modelId
            )
        }
        if (!replaceToolState(player, current, replacement)) {
            player.sendMessage(Component.text("Signal Wand settings expired; reopen the settings page")); return
        }
        sessions.invalidate(player.uniqueId)
        refreshStatus(player)
        player.sendMessage(Component.text(when (action) {
            is SignalWandSettingAction.Mode -> "Signal Wand mode selected: ${action.mode.label}"
            is SignalWandSettingAction.ControlKind -> "Signal Wand control selected: ${action.controlKind.label}"
            is SignalWandSettingAction.Type -> "Signal Wand type selected: ${action.signalType}"
            is SignalWandSettingAction.Model -> "Signal Wand presentation model selected: ${action.modelId}"
        }))
    }

    internal fun click(player: Player, state: SignalWandState, action: Action) {
        val playerId = player.uniqueId
        choiceTokens.remove(playerId)
        if (!canUse(player) || toolInHand(player) != state) {
            sessions.invalidate(playerId)
            if (operational() && !player.hasPermission(PERMISSION)) {
                player.sendMessage(Component.text("SimpleSignals: missing permission $PERMISSION"))
            }
            return
        }
        val operation = when {
            state.mode == SignalWandMode.DELETION &&
                (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) -> SignalWandOperation.DELETE
            state.mode == SignalWandMode.PLACEMENT &&
                (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) -> SignalWandOperation.BIND
            state.mode == SignalWandMode.PLACEMENT &&
                (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) -> SignalWandOperation.PLACE
            else -> {
                player.sendMessage(Component.text("Deletion mode uses left-click on a controlled entrance"))
                return
            }
        }
        if (operation == SignalWandOperation.PLACE && state.binding == null) {
            player.sendMessage(Component.text("Left-click the device binding target before placing it"))
            return
        }
        val worldId = player.world.uid
        val session = sessions.begin(playerId, worldId, state) ?: return
        val captured = try {
            backend.capture(worldId, state.signalType, state.presentationModelId)
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
        fun completeBindingOnOwner(binding: SignalWandBinding) {
            if (!ownerCurrent()) {
                sessions.invalidate(playerId, session)
                return
            }
            val revision = try {
                Math.addExact(state.stateRevision, 1L)
            } catch (_: ArithmeticException) {
                session.busy.set(false)
                player.sendMessage(Component.text("Signal Wand state revision overflow; replace this wand"))
                return
            }
            val replacement = state.copy(stateRevision = revision, binding = binding)
            if (!replaceToolState(player, state, replacement)) {
                sessions.invalidate(playerId, session)
                player.sendMessage(Component.text("Signal selection expired; select again"))
                return
            }
            sessions.invalidate(playerId, session)
            refreshStatus(player)
            player.sendMessage(Component.text(
                "Bound ${binding.description()}; right-click a rail to place the signal"
            ))
        }
        fun completeBinding(binding: SignalWandBinding) = entity(player) { completeBindingOnOwner(binding) }
        fun continueChoice(choices: List<SignalWandChoice>, deleting: Boolean) = entity(player) {
            if (!ownerCurrent()) {
                sessions.invalidate(playerId, session)
                return@entity
            }
            val token = Any()
            choiceTokens[playerId] = token
            session.busy.set(false)
            showChoices(player, choices) { choice ->
                entity(player) choiceOwner@{
                    if (choiceTokens.remove(playerId, token).not() || !ownerCurrent() ||
                        !session.busy.compareAndSet(false, true)) return@choiceOwner
                    worker {
                        if (!active()) return@worker
                        try {
                            require(backend.current(captured)) { "Signal selection expired; select again" }
                            if (deleting) complete(backend.delete(captured, choice.binding, ::active).message)
                            else completeBinding(choice.binding)
                        } catch (failure: Exception) {
                            complete("SimpleSignals: ${failure.message ?: failure.javaClass.simpleName}")
                        }
                    }
                }
            }
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
                            require(abs(dot) >= 0.15) { "Look along the rail into the controlled section" }
                            val point = RailPositionSnapshot(selected.rail().id,
                                if (dot >= 0) ViewDirection.AB else ViewDirection.BA, selected.arcLength())
                            when (operation) {
                                SignalWandOperation.BIND -> {
                                    val choices = backend.resolveEntrance(captured, state.controlKind, state.mode, point)
                                    if (state.controlKind == SignalWandControlKind.FIXED_BLOCK) {
                                        completeBinding(choices.single().binding)
                                    } else continueChoice(choices, deleting = false)
                                }
                                SignalWandOperation.PLACE -> complete(
                                    backend.place(captured, checkNotNull(state.binding), point, ::active).message)
                                SignalWandOperation.DELETE -> {
                                    val choices = backend.resolveEntrance(captured, state.controlKind, state.mode, point)
                                    if (state.controlKind == SignalWandControlKind.FIXED_BLOCK) {
                                        complete(backend.delete(captured, choices.single().binding, ::active).message)
                                    } else continueChoice(choices, deleting = true)
                                }
                            }
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
        player.sendActionBar(statusText(state, backend.availableSignalTypes(), backend.availableModelIds()))
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
        choiceTokens.remove(event.player.uniqueId)
        sessions.invalidate(event.player.uniqueId)
        stopStatus(event.player.uniqueId)
    }
    @EventHandler fun onWorldChange(event: PlayerChangedWorldEvent) {
        choiceTokens.remove(event.player.uniqueId)
        sessions.invalidate(event.player.uniqueId)
        deferRefresh(event.player)
    }
    @EventHandler(ignoreCancelled = true) fun onHeldItem(event: PlayerItemHeldEvent) {
        choiceTokens.remove(event.player.uniqueId)
        sessions.invalidate(event.player.uniqueId)
        deferRefresh(event.player)
    }
    @EventHandler(ignoreCancelled = true) fun onSwapHands(event: PlayerSwapHandItemsEvent) {
        choiceTokens.remove(event.player.uniqueId)
        sessions.invalidate(event.player.uniqueId)
        deferRefresh(event.player)
    }
    @EventHandler(ignoreCancelled = true) fun onInventoryClick(event: InventoryClickEvent) {
        choiceTokens.remove(event.whoClicked.uniqueId)
        sessions.invalidate(event.whoClicked.uniqueId)
        (event.whoClicked as? Player)?.let(::deferRefresh)
    }
    @EventHandler(ignoreCancelled = true) fun onInventoryDrag(event: InventoryDragEvent) {
        choiceTokens.remove(event.whoClicked.uniqueId)
        sessions.invalidate(event.whoClicked.uniqueId)
        (event.whoClicked as? Player)?.let(::deferRefresh)
    }
    @EventHandler(ignoreCancelled = true) fun onDrop(event: PlayerDropItemEvent) {
        choiceTokens.remove(event.player.uniqueId)
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
        choiceTokens.clear()
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
            PersistedSignalWandState(
                state.itemId.toString(),
                state.stateRevision,
                state.mode.name,
                state.controlKind.name,
                state.signalType,
                state.binding?.toPersisted(),
                state.presentationModelId
            )
        )

        internal fun decodeState(encoded: String): SignalWandState? = try {
            val persisted = STATE_JSON.decodeFromString<PersistedSignalWandState>(encoded)
            SignalWandState(
                UUID.fromString(persisted.itemId),
                persisted.stateRevision,
                SignalWandMode.valueOf(persisted.mode),
                SignalWandControlKind.valueOf(persisted.controlKind),
                persisted.signalType,
                persisted.binding?.toDomain(),
                persisted.presentationModelId
            )
        } catch (_: Exception) {
            null
        }

        internal fun createItem(plugin: Plugin, state: SignalWandState): ItemStack = ItemStack(Material.BLAZE_ROD).apply {
            editMeta { meta ->
                meta.displayName(Component.text("SimpleSignals Signal Wand"))
                writeTool(plugin, meta.persistentDataContainer, state)
                decorate(meta, state)
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
                decorate(meta, replacement)
            }
            return readTool(plugin, item) == replacement
        }

        internal fun statusText(
            state: SignalWandState,
            available: List<AvailableSignalType>?,
            availableModels: List<String>?
        ): Component {
            val availableNow = available?.any { it.signalType == state.signalType } == true
            val suffix = when {
                available == null -> " (catalog unavailable)"
                !availableNow -> " (unavailable)"
                else -> ""
            }
            val binding = state.binding?.let { " | bound: ${it.description()}" } ?: " | bound: none"
            val model = state.presentationModelId?.let {
                val modelSuffix = if (availableModels?.contains(it) == true) "" else " (unavailable)"
                " | model: $it$modelSuffix"
            } ?: " | model: none"
            return Component.text("Signal Wand | mode: ${state.mode.label} | control: ${state.controlKind.label} | " +
                "type: ${state.signalType}$suffix$model$binding")
        }

        internal fun decorate(meta: org.bukkit.inventory.meta.ItemMeta, state: SignalWandState) {
            meta.lore(listOf(
                Component.text("Shift-right-click: mode, control, signal type and model"),
                Component.text("Mode: ${state.mode.label}"),
                Component.text("Control: ${state.controlKind.label}"),
                Component.text("Selected type: ${state.signalType}"),
                Component.text("Presentation model: ${state.presentationModelId ?: "none"}"),
                Component.text(state.binding?.let { "Bound: ${it.description()}" } ?: "Bound: none")
            ))
            meta.setEnchantmentGlintOverride(state.binding != null)
        }

        private fun showNativeSettings(
            player: Player,
            settings: List<SignalWandSetting>,
            selected: (SignalWandSettingAction) -> Unit
        ) {
            val buttons = settings.map { setting ->
                val label = (if (setting.selected) "✓ " else "") +
                    setting.label.take(DISPLAY_NAME_LIMIT)
                val action = DialogAction.customClick(
                    { _, audience -> if (audience === player) selected(setting.action) },
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
                            "Select the mode, control, signal type and presentation model stored by this Wand.",
                            NamedTextColor.GRAY))))
                        .canCloseWithEscape(true)
                        .build())
                    .type(DialogType.multiAction(buttons, null, 1))
            }
            player.showDialog(dialog)
        }

        private fun showNativeChoices(
            player: Player,
            choices: List<SignalWandChoice>,
            selected: (SignalWandChoice) -> Unit
        ) {
            val buttons = choices.map { choice ->
                ActionButton.builder(Component.text(choice.label.take(DISPLAY_NAME_LIMIT), NamedTextColor.YELLOW))
                    .action(DialogAction.customClick(
                        { _, audience -> if (audience === player) selected(choice) },
                        ClickCallback.Options.builder().uses(1).lifetime(ClickCallback.DEFAULT_LIFETIME).build()))
                    .build()
            }
            player.showDialog(Dialog.create { factory -> factory.empty()
                .base(DialogBase.builder(Component.text("Select signal device", NamedTextColor.GOLD))
                    .body(listOf(DialogBody.plainMessage(Component.text(
                        "Choose one exact new or existing device.", NamedTextColor.GRAY))))
                    .canCloseWithEscape(true).build())
                .type(DialogType.multiAction(buttons, null, 1)) })
        }

        private const val DISPLAY_NAME_LIMIT = 64
        private const val MARKER_VALUE: Byte = 1
    }
}

internal data class SignalWandState(
    val itemId: UUID,
    val stateRevision: Long,
    val mode: SignalWandMode,
    val controlKind: SignalWandControlKind,
    val signalType: String,
    val binding: SignalWandBinding? = null,
    val presentationModelId: String? = null
) {
    init {
        require(stateRevision >= 0L) { "stateRevision must be non-negative" }
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(signalType)) { "signalType must be namespaced" }
        require(presentationModelId == null || SIMPLE_SIGNALS_NAMESPACED_ID.matches(presentationModelId)) {
            "presentationModelId must be namespaced"
        }
        require(binding == null || when (controlKind) {
            SignalWandControlKind.FIXED_BLOCK -> binding is FixedBlockSignalWandBinding
            SignalWandControlKind.PROTECTION_CURRENT_ENTRY -> binding is ProtectionCurrentEntrySignalWandBinding
            SignalWandControlKind.PROTECTION_FIXED_ROUTE -> binding is ProtectionFixedRouteSignalWandBinding
            SignalWandControlKind.ATTACHED_SIGNAL -> binding is AttachedSignalWandBinding
            SignalWandControlKind.ROUTE_INDICATOR -> binding is RouteIndicatorSignalWandBinding
        }) { "binding kind must match controlKind" }
    }
}

internal enum class SignalWandMode(val label: String) {
    PLACEMENT("placement"),
    DELETION("deletion")
}

internal enum class SignalWandControlKind(val label: String) {
    FIXED_BLOCK("fixed-block primary"),
    PROTECTION_CURRENT_ENTRY("protection current-entry primary"),
    PROTECTION_FIXED_ROUTE("protection fixed-route primary"),
    ATTACHED_SIGNAL("attached signal"),
    ROUTE_INDICATOR("route indicator")
}

internal sealed interface SignalWandBinding { val worldId: UUID }

internal data class FixedBlockSignalWandBinding(
    override val worldId: UUID,
    val blockId: String,
    val entryDirection: FixedBlockEntryDirection
) : SignalWandBinding {
    init {
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(blockId)) { "bound blockId must be namespaced" }
    }
}

internal sealed interface ProtectionSignalIntent
internal data object NewProtectionSignal : ProtectionSignalIntent
internal data class ExistingProtectionSignal(val signalId: String, val revision: Long) : ProtectionSignalIntent {
    init {
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(signalId)) { "signalId must be namespaced" }
        require(revision >= 0L) { "signal revision must be non-negative" }
    }
}

internal data class ProtectionCurrentEntrySignalWandBinding(
    override val worldId: UUID,
    val entryBoundaryId: String,
    val intent: ProtectionSignalIntent
) : SignalWandBinding {
    init { require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(entryBoundaryId)) { "entry boundary must be namespaced" } }
}

internal data class ProtectionFixedRouteSignalWandBinding(
    override val worldId: UUID,
    val entryBoundaryId: String,
    val routeId: String,
    val intent: ProtectionSignalIntent
) : SignalWandBinding {
    init {
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(entryBoundaryId)) { "entry boundary must be namespaced" }
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(routeId)) { "routeId must be namespaced" }
    }
}

internal sealed interface AttachedSignalIntent
internal data object NewAttachedSignal : AttachedSignalIntent
internal data class ExistingAttachedSignal(val signalId: String, val revision: Long) : AttachedSignalIntent {
    init {
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(signalId)) { "attached signalId must be namespaced" }
        require(revision >= 0L) { "attached signal revision must be non-negative" }
    }
}

internal data class AttachedSignalWandBinding(
    override val worldId: UUID,
    val primarySignalId: String,
    val primarySignalRevision: Long,
    val intent: AttachedSignalIntent
) : SignalWandBinding {
    init {
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(primarySignalId)) { "primary signalId must be namespaced" }
        require(primarySignalRevision >= 0L) { "primary signal revision must be non-negative" }
    }
}

internal data class RouteIndicatorSignalWandBinding(
    override val worldId: UUID,
    val entryBoundaryId: String,
    val indicatorId: String,
    val indicatorRevision: Long
) : SignalWandBinding {
    init {
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(entryBoundaryId)) { "entry boundary must be namespaced" }
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(indicatorId)) { "indicatorId must be namespaced" }
        require(indicatorRevision >= 0L) { "indicator revision must be non-negative" }
    }
}

private fun SignalWandBinding.description(): String = when (this) {
    is FixedBlockSignalWandBinding -> "$blockId $entryDirection"
    is ProtectionCurrentEntrySignalWandBinding -> "$entryBoundaryId current-entry ${intent.description()}"
    is ProtectionFixedRouteSignalWandBinding -> "$entryBoundaryId route $routeId ${intent.description()}"
    is AttachedSignalWandBinding -> "$primarySignalId attached ${intent.description()}"
    is RouteIndicatorSignalWandBinding -> "$entryBoundaryId indicator $indicatorId"
}

private fun ProtectionSignalIntent.description(): String = when (this) {
    NewProtectionSignal -> "new"
    is ExistingProtectionSignal -> signalId
}

private fun AttachedSignalIntent.description(): String = when (this) {
    NewAttachedSignal -> "new"
    is ExistingAttachedSignal -> signalId
}

@Serializable
private data class PersistedSignalWandState(
    val itemId: String,
    val stateRevision: Long,
    val mode: String,
    val controlKind: String,
    val signalType: String,
    val binding: PersistedSignalWandBinding? = null,
    val presentationModelId: String? = null
)

@Serializable
private sealed interface PersistedSignalWandBinding

@Serializable
@SerialName("fixed_block")
private data class PersistedFixedBlockSignalWandBinding(
    val worldId: String,
    val blockId: String,
    val entryDirection: String
) : PersistedSignalWandBinding

@Serializable
@SerialName("protection_current_entry")
private data class PersistedProtectionCurrentEntrySignalWandBinding(
    val worldId: String,
    val entryBoundaryId: String,
    val existingSignalId: String? = null,
    val existingSignalRevision: Long? = null
) : PersistedSignalWandBinding

@Serializable
@SerialName("protection_fixed_route")
private data class PersistedProtectionFixedRouteSignalWandBinding(
    val worldId: String,
    val entryBoundaryId: String,
    val routeId: String,
    val existingSignalId: String? = null,
    val existingSignalRevision: Long? = null
) : PersistedSignalWandBinding

@Serializable
@SerialName("attached_signal")
private data class PersistedAttachedSignalWandBinding(
    val worldId: String,
    val primarySignalId: String,
    val primarySignalRevision: Long,
    val existingSignalId: String? = null,
    val existingSignalRevision: Long? = null
) : PersistedSignalWandBinding

@Serializable
@SerialName("route_indicator")
private data class PersistedRouteIndicatorSignalWandBinding(
    val worldId: String,
    val entryBoundaryId: String,
    val indicatorId: String,
    val indicatorRevision: Long
) : PersistedSignalWandBinding

private fun SignalWandBinding.toPersisted(): PersistedSignalWandBinding = when (this) {
    is FixedBlockSignalWandBinding -> PersistedFixedBlockSignalWandBinding(
        worldId.toString(), blockId, entryDirection.name)
    is ProtectionCurrentEntrySignalWandBinding -> PersistedProtectionCurrentEntrySignalWandBinding(
        worldId.toString(), entryBoundaryId, intent.existingId(), intent.existingRevision())
    is ProtectionFixedRouteSignalWandBinding -> PersistedProtectionFixedRouteSignalWandBinding(
        worldId.toString(), entryBoundaryId, routeId, intent.existingId(), intent.existingRevision())
    is AttachedSignalWandBinding -> PersistedAttachedSignalWandBinding(
        worldId.toString(), primarySignalId, primarySignalRevision,
        intent.existingId(), intent.existingRevision())
    is RouteIndicatorSignalWandBinding -> PersistedRouteIndicatorSignalWandBinding(
        worldId.toString(), entryBoundaryId, indicatorId, indicatorRevision)
}

private fun PersistedSignalWandBinding.toDomain(): SignalWandBinding = when (this) {
    is PersistedFixedBlockSignalWandBinding -> FixedBlockSignalWandBinding(
        UUID.fromString(worldId), blockId, FixedBlockEntryDirection.valueOf(entryDirection))
    is PersistedProtectionCurrentEntrySignalWandBinding -> ProtectionCurrentEntrySignalWandBinding(
        UUID.fromString(worldId), entryBoundaryId, protectionIntent(existingSignalId, existingSignalRevision))
    is PersistedProtectionFixedRouteSignalWandBinding -> ProtectionFixedRouteSignalWandBinding(
        UUID.fromString(worldId), entryBoundaryId, routeId,
        protectionIntent(existingSignalId, existingSignalRevision))
    is PersistedAttachedSignalWandBinding -> AttachedSignalWandBinding(
        UUID.fromString(worldId), primarySignalId, primarySignalRevision,
        attachedIntent(existingSignalId, existingSignalRevision))
    is PersistedRouteIndicatorSignalWandBinding -> RouteIndicatorSignalWandBinding(
        UUID.fromString(worldId), entryBoundaryId, indicatorId, indicatorRevision)
}

private fun ProtectionSignalIntent.existingId() = (this as? ExistingProtectionSignal)?.signalId
private fun ProtectionSignalIntent.existingRevision() = (this as? ExistingProtectionSignal)?.revision
private fun protectionIntent(id: String?, revision: Long?): ProtectionSignalIntent {
    require((id == null) == (revision == null)) { "existing signal identity must include both ID and revision" }
    return if (id == null) NewProtectionSignal else ExistingProtectionSignal(id, checkNotNull(revision))
}
private fun AttachedSignalIntent.existingId() = (this as? ExistingAttachedSignal)?.signalId
private fun AttachedSignalIntent.existingRevision() = (this as? ExistingAttachedSignal)?.revision
private fun attachedIntent(id: String?, revision: Long?): AttachedSignalIntent {
    require((id == null) == (revision == null)) { "existing attached identity must include both ID and revision" }
    return if (id == null) NewAttachedSignal else ExistingAttachedSignal(id, checkNotNull(revision))
}

internal data class SignalWandSetting(
    val action: SignalWandSettingAction,
    val label: String,
    val selected: Boolean
)

internal sealed interface SignalWandSettingAction {
    data class Mode(val mode: SignalWandMode) : SignalWandSettingAction
    data class ControlKind(val controlKind: SignalWandControlKind) : SignalWandSettingAction
    data class Type(val signalType: String) : SignalWandSettingAction
    data class Model(val modelId: String) : SignalWandSettingAction
}

private enum class SignalWandOperation { BIND, PLACE, DELETE }

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
