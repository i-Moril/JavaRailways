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
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
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
import org.joml.Matrix3d
import org.joml.Quaterniond
import org.joml.Vector3d
import work.art1st.javarailways.bukkit.api.rail.RailTarget
import work.art1st.javarailways.bukkit.api.rail.RailTargetService
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simplesignals.region.SIMPLE_SIGNALS_NAMESPACED_ID
import work.art1st.javarailways.simplesignals.presentation.ModelPlacement
import work.art1st.javarailways.simplesignals.presentation.RailPlacementPreset
import work.art1st.javarailways.simplesignals.signal.FixedBlockEntryDirection
import work.art1st.javarailways.simplesignals.signalpack.AvailableSignalType
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

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
        require(player.inventory.firstEmpty() >= 0) { WandI18n.format(player, "inventory.full") }
        player.inventory.addItem(createItem(plugin, state, player.locale()))
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
        { player -> targets.findLookedAtRail(player, 64.0, 0.75) },
    private val showAuthoringDialog: (Player, String, List<Pair<String, () -> Unit>>) -> Unit = WandDialogs::show,
    private val showPlacementInput: (Player, String, PlacementWandInput, (PlacementWandInput?) -> Unit) -> Unit = WandDialogs::placement,
    private val checkOverlay: (Player) -> Unit = {}
) : Listener, CommandExecutor {
    private val sessions = SignalWandSessions()
    private val statusTasks = ConcurrentHashMap<UUID, SignalWandStatusRegistration>()
    private val choiceTokens = ConcurrentHashMap<UUID, Any>()
    private val closed = AtomicBoolean(false)

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!operational()) return true
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(Component.text(WandI18n.format(sender, "permission", PERMISSION))); return true
        }
        if (sender !is Player) {
            sender.sendMessage(Component.text(WandI18n.format(sender, "signal.player"))); return true
        }
        if (args.size != 2 || args[0] != "wand" || args[1] != "signal") {
            sender.sendMessage(Component.text(USAGE)); return true
        }
        val available = currentTypes()
        if (available == null) {
            sender.sendMessage(Component.text(WandI18n.format(sender, "signal.catalog"))); return true
        }
        if (available.isEmpty()) {
            sender.sendMessage(Component.text(WandI18n.format(sender, "signal.empty"))); return true
        }
        val state = SignalWandState(newItemId(), 0L, SignalWandMode.PLACEMENT,
            SignalWandControlKind.FIXED_BLOCK, available.first().signalType,
            presentationModelId = backend.availableModelIds()?.firstOrNull())
        sessions.invalidate(sender.uniqueId)
        try {
            giveTool(sender, state)
            checkOverlay(sender)
            refreshStatus(sender)
            sender.sendMessage(Component.text(
                WandI18n.format(sender, "signal.created")
            ))
        } catch (failure: Exception) {
            sender.sendMessage(Component.text(failure.message ?: WandI18n.format(sender, "signal.create.failed")))
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
            val groundPlacement = if (event.action == Action.RIGHT_CLICK_BLOCK) {
                event.clickedBlock?.let { clicked -> groundPlacement(clicked, event.blockFace, player.location.yaw) }
            } else null
            click(player, state, event.action, groundPlacement)
        }
    }

    internal fun openSettings(player: Player, openingState: SignalWandState) {
        if (!canUse(player) || toolInHand(player) != openingState) return
        checkOverlay(player)
        choiceTokens.remove(player.uniqueId)
        val available = currentTypes()
        if (available == null) {
            player.sendMessage(Component.text(WandI18n.format(player, "signal.catalog"))); return
        }
        if (available.isEmpty()) {
            player.sendMessage(Component.text(WandI18n.format(player, "signal.empty"))); return
        }
        val railModel = backend.railPlacementModel(openingState.controlKind, openingState.signalType,
            openingState.presentationModelId)
        val settings = SignalWandMode.entries.map { mode ->
            SignalWandSetting(SignalWandSettingAction.Mode(mode), WandI18n.format(player, "signal.mode", WandI18n.format(player, "mode.${mode.label}")), mode == openingState.mode)
        } + SignalWandControlKind.entries.map { kind ->
            SignalWandSetting(SignalWandSettingAction.ControlKind(kind),
                WandI18n.format(player, "signal.control", WandI18n.format(player, "control.${kind.name}")), kind == openingState.controlKind)
        } + available.map {
            SignalWandSetting(SignalWandSettingAction.Type(it.signalType),
                "${it.signalType} — ${it.displayName}", it.signalType == openingState.signalType)
        } + backend.availableModelIds().orEmpty().map { modelId ->
            SignalWandSetting(SignalWandSettingAction.Model(modelId),
                WandI18n.format(player, "signal.model", modelId), modelId == openingState.presentationModelId)
        } + SignalWandSetting(SignalWandSettingAction.ClearRailPreset, WandI18n.format(player, "signal.preset.clear"),
            openingState.railPreset == null) + railModel?.presets.orEmpty().keys.map { name ->
            SignalWandSetting(SignalWandSettingAction.RailPreset(checkNotNull(railModel).modelId, name),
                WandI18n.format(player, "signal.preset", name), openingState.railPreset?.let {
                    it.modelId == railModel.modelId && it.name == name
                } == true)
        } + SignalWandSetting(SignalWandSettingAction.RebindSignal, WandI18n.format(player, "signal.rebind"), false) +
            SignalWandSetting(SignalWandSettingAction.EditIndicator, WandI18n.format(player, "signal.indicator.edit"), false) +
            SignalWandSetting(SignalWandSettingAction.EditPlacement, WandI18n.format(player, "signal.placement.edit"), false)
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
            player.sendMessage(Component.text(WandI18n.format(player, "signal.expired"))); return
        }
        if (action === SignalWandSettingAction.RebindSignal) {
            openRebind(player, current)
            return
        }
        if (action === SignalWandSettingAction.EditIndicator) {
            openIndicatorEditor(player, current)
            return
        }
        if (action === SignalWandSettingAction.EditPlacement) {
            openPlacementEditor(player, current)
            return
        }
        val available = currentTypes()
        if (action is SignalWandSettingAction.Type && available == null) {
            player.sendMessage(Component.text(WandI18n.format(player, "signal.catalog"))); return
        }
        if (action is SignalWandSettingAction.Type && available.orEmpty().none { it.signalType == action.signalType }) {
            player.sendMessage(Component.text(WandI18n.format(player, "signal.type.missing", action.signalType))); return
        }
        if (action is SignalWandSettingAction.Model &&
            backend.availableModelIds()?.contains(action.modelId) != true) {
            player.sendMessage(Component.text(WandI18n.format(player, "signal.model.missing", action.modelId))); return
        }
        val selectedPreset = if (action is SignalWandSettingAction.RailPreset) {
            val model = backend.railPlacementModel(current.controlKind, current.signalType, current.presentationModelId)
            val preset = model?.takeIf { it.modelId == action.modelId }?.presets?.get(action.name)
            if (preset == null) {
                player.sendMessage(Component.text(WandI18n.format(player, "signal.preset.missing", action.name))); return
            }
            SignalWandRailPreset(action.modelId, action.name, preset.adjustment, checkNotNull(preset.followRailTilt))
        } else null
        val revision = try {
            Math.addExact(current.stateRevision, 1L)
        } catch (_: ArithmeticException) {
            player.sendMessage(Component.text(WandI18n.format(player, "signal.overflow"))); return
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
                binding = if (action.controlKind == current.controlKind) current.binding else null,
                railPreset = if (action.controlKind == current.controlKind) current.railPreset else null
            )
            is SignalWandSettingAction.Type -> current.copy(
                stateRevision = revision,
                signalType = action.signalType,
                railPreset = current.railPreset?.takeIf { preset ->
                    backend.railPlacementModel(current.controlKind, action.signalType,
                        current.presentationModelId)?.modelId == preset.modelId
                }
            )
            is SignalWandSettingAction.Model -> current.copy(
                stateRevision = revision,
                presentationModelId = action.modelId,
                railPreset = current.railPreset?.takeIf { preset ->
                    backend.railPlacementModel(current.controlKind, current.signalType, action.modelId)?.modelId == preset.modelId
                }
            )
            is SignalWandSettingAction.RailPreset -> current.copy(stateRevision = revision, railPreset = selectedPreset)
            SignalWandSettingAction.ClearRailPreset -> current.copy(stateRevision = revision, railPreset = null)
            SignalWandSettingAction.RebindSignal -> return
            SignalWandSettingAction.EditIndicator -> return
            SignalWandSettingAction.EditPlacement -> return
        }
        if (!replaceToolState(player, current, replacement)) {
            player.sendMessage(Component.text(WandI18n.format(player, "signal.expired"))); return
        }
        sessions.invalidate(player.uniqueId)
        refreshStatus(player)
        player.sendMessage(Component.text(when (action) {
            is SignalWandSettingAction.Mode -> WandI18n.format(player, "signal.mode.selected", WandI18n.format(player, "mode.${action.mode.label}"))
            is SignalWandSettingAction.ControlKind -> WandI18n.format(player, "signal.control.selected", WandI18n.format(player, "control.${action.controlKind.name}"))
            is SignalWandSettingAction.Type -> WandI18n.format(player, "signal.type.selected", action.signalType)
            is SignalWandSettingAction.Model -> WandI18n.format(player, "signal.model.selected", action.modelId)
            is SignalWandSettingAction.RailPreset -> WandI18n.format(player, "signal.preset.selected", action.name)
            SignalWandSettingAction.ClearRailPreset -> WandI18n.format(player, "signal.preset.reset")
            SignalWandSettingAction.RebindSignal -> return
            SignalWandSettingAction.EditIndicator -> return
            SignalWandSettingAction.EditPlacement -> return
        }))
    }

    private fun openRebind(player: Player, state: SignalWandState) {
        val locale = player.locale()
        val playerId = player.uniqueId
        val worldId = player.world.uid
        sessions.invalidate(playerId)
        val session = sessions.begin(playerId, worldId, state) ?: return
        val token = Any()
        choiceTokens[playerId] = token
        fun active() = sessions.current(playerId, session) && operational() && choiceTokens[playerId] === token
        fun ownerCurrent() = active() && player.isOnline && player.world.uid == worldId &&
            player.hasPermission(PERMISSION) && toolInHand(player) == state
        fun reply(message: String) = entity(player) {
            if (ownerCurrent()) player.sendMessage(Component.text(message.take(MESSAGE_LIMIT)))
            session.busy.set(false)
        }
        fun work(action: () -> Unit) {
            try { worker {
                if (!active()) { session.busy.set(false); return@worker }
                try { action() } catch (failure: Exception) {
                    reply("SimpleSignals: ${failure.message ?: failure.javaClass.simpleName}")
                }
            } } catch (failure: Exception) { reply(WandI18n.format(locale, "signal.rebind.failed", failure.message)) }
        }
        fun page(title: String, options: List<Pair<String, () -> Unit>>) = entity(player) {
            session.busy.set(false)
            if (!ownerCurrent()) return@entity
            val used = AtomicBoolean(false)
            showAuthoringDialog(player, title, options.map { (label, action) -> label to {
                entity(player) {
                    if (ownerCurrent() && used.compareAndSet(false, true) && session.busy.compareAndSet(false, true))
                        work(action)
                }
            } })
        }
        work {
            val capture = backend.capture(worldId, state.signalType, state.presentationModelId)
            val devices = backend.rebindDevices(capture)
            require(devices.isNotEmpty()) { WandI18n.format(locale, "signal.rebind.empty") }
            page(WandI18n.format(locale, "signal.rebind.choose"), devices.mapIndexed { index, device ->
                WandI18n.format(locale, "signal.rebind.device", index + 1, authoringName(device.signalId, "name.signal"), WandI18n.format(locale, "control.${device.controlKind.name}"), device.bindingId?.let { authoringName(it, "name.route") } ?: WandText.key("none")) to {
                    require(backend.current(capture)) { WandI18n.format(locale, "signal.selection.expired") }
                    val targets = backend.rebindTargets(capture, device)
                    require(targets.isNotEmpty()) { WandI18n.format(locale, "signal.rebind.target.empty") }
                    page(WandI18n.format(locale, "signal.rebind.target"), targets.mapIndexed { targetIndex, target ->
                        WandI18n.format(locale, "signal.rebind.entry", targetIndex + 1, target.description) to {
                            require(backend.current(capture)) { WandI18n.format(locale, "signal.selection.expired") }
                            page(WandI18n.format(locale, "signal.rebind.confirm", authoringName(device.signalId, "name.signal").render(locale)), listOf(
                                WandI18n.format(locale, "signal.rebind.accept", target.description.render(locale)) to {
                                    reply(backend.rebind(capture, device, target, ::active).text.render(locale))
                                },
                                WandI18n.format(locale, "cancel") to { reply(WandI18n.format(locale, "signal.rebind.cancelled")) }
                            ))
                        }
                    })
                }
            })
        }
    }

    private fun openIndicatorEditor(player: Player, state: SignalWandState,
        creationCapture: SignalWandCapture? = null, binding: RouteIndicatorSignalWandBinding? = null,
        placement: ModelPlacement? = null) {
        val locale = player.locale()
        val playerId = player.uniqueId
        val worldId = player.world.uid
        sessions.invalidate(playerId)
        val session = sessions.begin(playerId, worldId, state) ?: return
        val token = Any()
        choiceTokens[playerId] = token
        fun active() = sessions.current(playerId, session) && operational() && choiceTokens[playerId] === token
        fun ownerCurrent() = active() && player.isOnline && player.world.uid == worldId &&
            player.hasPermission(PERMISSION) && toolInHand(player) == state
        fun reply(message: String) = entity(player) {
            if (ownerCurrent()) player.sendMessage(Component.text(message.take(MESSAGE_LIMIT)))
            choiceTokens.remove(playerId, token)
            session.busy.set(false)
        }
        fun work(action: () -> Unit) {
            try { worker {
                if (!active()) { session.busy.set(false); return@worker }
                try { action() } catch (failure: Exception) {
                    reply("SimpleSignals: ${failure.message ?: failure.javaClass.simpleName}")
                }
            } } catch (failure: Exception) { reply(WandI18n.format(locale, "signal.indicator.failed", failure.message)) }
        }
        fun page(title: String, options: List<Pair<String, () -> Unit>>) = entity(player) {
            session.busy.set(false)
            if (!ownerCurrent()) return@entity
            val used = AtomicBoolean(false)
            showAuthoringDialog(player, title, options.map { (label, action) -> label to {
                entity(player) {
                    if (ownerCurrent() && used.compareAndSet(false, true) && session.busy.compareAndSet(false, true))
                        work(action)
                }
            } })
        }
        work {
            val capture = creationCapture ?: backend.capture(worldId, state.signalType)
            require(backend.current(capture)) { WandI18n.format(locale, "signal.selection.expired") }
            val editor = RouteIndicatorWandEditor(backend, capture, ::page, ::reply, ::active, locale)
            if (binding == null) editor.selectDevice() else {
                val entry = requireNotNull(binding.entryBoundaryId) { WandI18n.format(locale, "signal.rebind.target.empty") }
                editor.configure(binding, entry, placement)
            }
        }
    }

    private fun openPlacementEditor(player: Player, state: SignalWandState) {
        val locale = player.locale()
        val playerId = player.uniqueId
        val worldId = player.world.uid
        sessions.invalidate(playerId)
        val session = sessions.begin(playerId, worldId, state) ?: return
        val token = Any()
        choiceTokens[playerId] = token
        fun active() = sessions.current(playerId, session) && operational() && choiceTokens[playerId] === token
        fun ownerCurrent() = active() && player.isOnline && player.world.uid == worldId &&
            player.hasPermission(PERMISSION) && toolInHand(player) == state
        fun reply(message: String) = entity(player) {
            if (ownerCurrent()) player.sendMessage(Component.text(message.take(MESSAGE_LIMIT)))
            choiceTokens.remove(playerId, token)
            session.busy.set(false)
        }
        fun work(action: () -> Unit) {
            try { worker {
                if (!active()) { session.busy.set(false); return@worker }
                try { action() } catch (failure: Exception) {
                    reply("SimpleSignals: ${failure.message ?: failure.javaClass.simpleName}")
                }
            } } catch (failure: Exception) { reply(WandI18n.format(locale, "signal.placement.failed", failure.message)) }
        }
        fun page(title: String, options: List<Pair<String, () -> Unit>>) = entity(player) {
            session.busy.set(false)
            if (!ownerCurrent()) return@entity
            val used = AtomicBoolean(false)
            showAuthoringDialog(player, title, options.map { (label, action) -> label to {
                entity(player) {
                    if (ownerCurrent() && used.compareAndSet(false, true) && session.busy.compareAndSet(false, true))
                        work(action)
                }
            } })
        }
        fun input(title: String, initial: PlacementWandInput, submit: (PlacementWandInput?) -> Unit) = entity(player) {
            session.busy.set(false)
            if (!ownerCurrent()) return@entity
            val used = AtomicBoolean(false)
            showPlacementInput(player, title, initial) { submitted -> entity(player) {
                if (ownerCurrent() && used.compareAndSet(false, true) && session.busy.compareAndSet(false, true))
                    work { submit(submitted) }
            } }
        }
        work {
            val capture = backend.capture(worldId, state.signalType)
            PlacementWandEditor(backend, capture, ::page, ::input, ::reply, ::active, locale).selectDevice()
        }
    }

    internal fun click(
        player: Player,
        state: SignalWandState,
        action: Action,
        groundPlacement: ModelPlacement.Ground? = null
    ) {
        val locale = player.locale()
        val playerId = player.uniqueId
        choiceTokens.remove(playerId)
        if (!canUse(player) || toolInHand(player) != state) {
            sessions.invalidate(playerId)
            if (operational() && !player.hasPermission(PERMISSION)) {
                player.sendMessage(Component.text(WandI18n.format(locale, "permission", PERMISSION)))
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
                player.sendMessage(Component.text(WandI18n.format(locale, "signal.delete.hint")))
                return
            }
        }
        if (operation == SignalWandOperation.PLACE && state.binding == null) {
            player.sendMessage(Component.text(WandI18n.format(locale, "signal.bind.hint")))
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
        val sight = if (operation == SignalWandOperation.PLACE && groundPlacement != null) {
            null
        } else {
            player.eyeLocation.direction
        }
        fun active(): Boolean = sessions.current(playerId, session) && operational()
        fun ownerCurrent(): Boolean = active() && player.isOnline && player.world.uid == worldId &&
            player.hasPermission(PERMISSION) && toolInHand(player) == state
        fun complete(message: String) = entity(player) {
            if (ownerCurrent()) player.sendMessage(Component.text(message.take(MESSAGE_LIMIT)))
            session.busy.set(false)
        }
        fun place(placement: ModelPlacement) {
            val binding = checkNotNull(state.binding)
            if (binding is RouteIndicatorSignalWandBinding && binding.indicatorId == null) {
                entity(player) {
                    if (ownerCurrent()) openIndicatorEditor(player, state, captured, binding, placement)
                    else sessions.invalidate(playerId, session)
                }
            } else complete(backend.place(captured, binding, placement, ::active).text.render(locale))
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
                player.sendMessage(Component.text(WandI18n.format(locale, "signal.overflow")))
                return
            }
            val replacement = state.copy(stateRevision = revision, binding = binding)
            if (!replaceToolState(player, state, replacement)) {
                sessions.invalidate(playerId, session)
                player.sendMessage(Component.text(WandI18n.format(locale, "signal.selection.expired")))
                return
            }
            sessions.invalidate(playerId, session)
            refreshStatus(player)
            player.sendMessage(Component.text(
                WandI18n.format(locale, "signal.bound.hint", binding.description(locale))
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
            showChoices(player, choices.mapIndexed { index, choice ->
                choice.copy(label = WandText.key("choice.numbered", index + 1, choice.label))
            }) { choice ->
                entity(player) choiceOwner@{
                    if (choiceTokens.remove(playerId, token).not() || !ownerCurrent() ||
                        !session.busy.compareAndSet(false, true)) return@choiceOwner
                    worker {
                        if (!active()) return@worker
                        try {
                            require(backend.current(captured)) { WandI18n.format(locale, "signal.selection.expired") }
                            if (deleting) complete(backend.delete(captured, choice.binding, ::active).text.render(locale))
                            else completeBinding(choice.binding)
                        } catch (failure: Exception) {
                            complete("SimpleSignals: ${failure.message ?: failure.javaClass.simpleName}")
                        }
                    }
                }
            }
        }
        if (operation == SignalWandOperation.PLACE && groundPlacement != null) {
            worker {
                if (!active()) return@worker
                try {
                    require(backend.current(captured)) { WandI18n.format(locale, "signal.selection.expired") }
                    place(groundPlacement)
                } catch (failure: Exception) {
                    complete("SimpleSignals: ${failure.message ?: failure.javaClass.simpleName}")
                }
            }
            return
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
                            require(backend.current(captured)) { WandI18n.format(locale, "signal.selection.expired") }
                            val curve: Curve = targets.findCurve(worldId, selected.rail().id)
                                .orElseThrow { IllegalArgumentException("Rail no longer exists") }
                            val tangent = curve.getPointAt(selected.arcLength()).tangent
                            val selectedSight = checkNotNull(sight)
                            val dot = tangent.x() * selectedSight.x + tangent.y() * selectedSight.y +
                                tangent.z() * selectedSight.z
                            require(abs(dot) >= 0.15) { WandI18n.format(locale, "signal.direction") }
                            val point = RailPositionSnapshot(selected.rail().id,
                                if (dot >= 0) ViewDirection.AB else ViewDirection.BA, selected.arcLength())
                            when (operation) {
                                SignalWandOperation.BIND -> {
                                    val choices = backend.resolveEntrance(captured, state.controlKind, state.mode, point)
                                    if (state.controlKind == SignalWandControlKind.FIXED_BLOCK) {
                                        completeBinding(choices.single().binding)
                                    } else continueChoice(choices, deleting = false)
                                }
                                SignalWandOperation.PLACE -> {
                                    val placement = state.railPreset?.let { preset ->
                                        require(backend.railPlacementModel(state.controlKind, state.signalType,
                                            state.presentationModelId)?.modelId == preset.modelId) {
                                            WandI18n.format(locale, "signal.model.changed")
                                        }
                                        ModelPlacement.RailAttached(point).applyPreset(
                                            RailPlacementPreset(preset.adjustment, preset.followRailTilt))
                                    } ?: ModelPlacement.RailAttached(point)
                                    place(placement)
                                }
                                SignalWandOperation.DELETE -> {
                                    val choices = backend.resolveEntrance(captured, state.controlKind, state.mode, point)
                                    if (state.controlKind == SignalWandControlKind.FIXED_BLOCK) {
                                        complete(backend.delete(captured, choices.single().binding, ::active).text.render(locale))
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
            player.sendMessage(Component.text(WandI18n.format(locale, "rail.failed", failure.message?.take(256))))
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
        player.sendActionBar(statusText(state, backend.availableSignalTypes(), backend.availableModelIds(), player.locale()))
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
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSwapHands(event: PlayerSwapHandItemsEvent) {
        work.art1st.javarailways.bukkit.api.wand.WandModeSwitch.handle(event,
            { readTool(plugin, it) != null }) { player ->
            choiceTokens.remove(player.uniqueId)
            sessions.invalidate(player.uniqueId)
            val state = toolInHand(player) ?: return@handle
            selectSetting(player, state, SignalWandSettingAction.Mode(
                if (state.mode == SignalWandMode.PLACEMENT) SignalWandMode.DELETION else SignalWandMode.PLACEMENT))
            checkOverlay(player)
        }
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
        private val STATE_JSON = Json { encodeDefaults = true; explicitNulls = true }
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
                state.presentationModelId,
                state.railPreset?.let { preset ->
                    val pose = preset.adjustment
                    PersistedSignalWandRailPreset(preset.modelId, preset.name,
                        pose.x(), pose.y(), pose.z(), pose.rotationX(), pose.rotationY(), pose.rotationZ(),
                        pose.rotationW(), preset.followRailTilt)
                }
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
                persisted.presentationModelId,
                persisted.railPreset?.let { preset -> SignalWandRailPreset(preset.modelId, preset.name,
                    ModelPose(preset.x, preset.y, preset.z, preset.rotationX, preset.rotationY, preset.rotationZ,
                        preset.rotationW), preset.followRailTilt) }
            )
        } catch (_: Exception) {
            null
        }

        internal fun createItem(plugin: Plugin, state: SignalWandState, locale: java.util.Locale = java.util.Locale.US): ItemStack = ItemStack(Material.BLAZE_ROD).apply {
            editMeta { meta ->
                meta.displayName(Component.text(WandI18n.format(locale, "signal.name")))
                writeTool(plugin, meta.persistentDataContainer, state)
                decorate(meta, state, locale)
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
                decorate(meta, replacement, player.locale())
            }
            return readTool(plugin, item) == replacement
        }

        internal fun statusText(
            state: SignalWandState,
            available: List<AvailableSignalType>?,
            availableModels: List<String>?,
            locale: java.util.Locale = java.util.Locale.US
        ): Component {
            val availableNow = available?.any { it.signalType == state.signalType } == true
            val suffix = when {
                available == null -> WandI18n.format(locale, "catalog.unavailable")
                !availableNow -> WandI18n.format(locale, "unavailable")
                else -> ""
            }
            val modelSuffix = if (state.presentationModelId != null && availableModels?.contains(state.presentationModelId) != true)
                WandI18n.format(locale, "unavailable") else ""
            return Component.text(WandI18n.format(locale, "signal.status",
                WandI18n.format(locale, "mode." + state.mode.label), WandI18n.format(locale, "control." + state.controlKind.name),
                state.signalType + suffix, (state.presentationModelId ?: WandI18n.format(locale, "none")) + modelSuffix,
                state.railPreset?.name ?: WandI18n.format(locale, "none"),
                state.binding?.description(locale) ?: WandI18n.format(locale, "none")))
        }

        internal fun decorate(meta: org.bukkit.inventory.meta.ItemMeta, state: SignalWandState, locale: java.util.Locale = java.util.Locale.US) {
            meta.displayName(Component.text(WandI18n.format(locale, "signal.name")))
            meta.lore(listOf(
                Component.text(WandI18n.format(locale, "signal.lore")),
                Component.text(WandI18n.format(locale, "signal.mode", WandI18n.format(locale, "mode." + state.mode.label))),
                Component.text(WandI18n.format(locale, "signal.control", WandI18n.format(locale, "control." + state.controlKind.name))),
                Component.text(WandI18n.format(locale, "signal.type", state.signalType)),
                Component.text(WandI18n.format(locale, "signal.model", state.presentationModelId ?: WandI18n.format(locale, "none"))),
                Component.text(WandI18n.format(locale, "signal.preset", state.railPreset?.name ?: WandI18n.format(locale, "none"))),
                Component.text(WandI18n.format(locale, "signal.bound", state.binding?.description(locale) ?: WandI18n.format(locale, "none")))
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
                    .base(DialogBase.builder(Component.text(WandI18n.format(player, "signal.settings.title"), NamedTextColor.GOLD))
                        .body(listOf(DialogBody.plainMessage(Component.text(
                            WandI18n.format(player, "signal.settings.body"),
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
                ActionButton.builder(Component.text(choice.label.render(player.locale()).take(DISPLAY_NAME_LIMIT), NamedTextColor.YELLOW))
                    .action(DialogAction.customClick(
                        { _, audience -> if (audience === player) selected(choice) },
                        ClickCallback.Options.builder().uses(1).lifetime(ClickCallback.DEFAULT_LIFETIME).build()))
                    .build()
            }
            player.showDialog(Dialog.create { factory -> factory.empty()
                .base(DialogBase.builder(Component.text(WandI18n.format(player, "signal.choose.title"), NamedTextColor.GOLD))
                    .body(listOf(DialogBody.plainMessage(Component.text(
                        WandI18n.format(player, "signal.choose.body"), NamedTextColor.GRAY))))
                    .canCloseWithEscape(true).build())
                .type(DialogType.multiAction(buttons, null, 1)) })
        }

        private const val DISPLAY_NAME_LIMIT = 64
        private const val MARKER_VALUE: Byte = 1

        /**
         * Places the model root at the clicked face centre. It stays upright and points its local +X axis
         * along the player's horizontal facing; local +Y remains world-up and local +Z is the player's right.
         */
        internal fun groundPlacement(block: Block, face: BlockFace, playerYawDegrees: Float): ModelPlacement.Ground =
            groundPlacement(block.x, block.y, block.z, face, playerYawDegrees)

        internal fun groundPlacement(
            blockX: Int,
            blockY: Int,
            blockZ: Int,
            face: BlockFace,
            playerYawDegrees: Float
        ): ModelPlacement.Ground {
            require(playerYawDegrees.isFinite()) { "Player facing must be finite" }
            val yaw = Math.toRadians(playerYawDegrees.toDouble())
            val forward = Vector3d(-sin(yaw), 0.0, cos(yaw))
            val up = Vector3d(0.0, 1.0, 0.0)
            val right = Vector3d(forward).cross(up)
            val rotation = Quaterniond().setFromNormalized(
                Matrix3d().setColumn(0, forward).setColumn(1, up).setColumn(2, right)
            ).normalize()
            return ModelPlacement.Ground(ModelPose(
                blockX + 0.5 + face.modX * 0.5,
                blockY + 0.5 + face.modY * 0.5,
                blockZ + 0.5 + face.modZ * 0.5,
                rotation.x, rotation.y, rotation.z, rotation.w
            ))
        }
    }
}

internal data class SignalWandState(
    val itemId: UUID,
    val stateRevision: Long,
    val mode: SignalWandMode,
    val controlKind: SignalWandControlKind,
    val signalType: String,
    val binding: SignalWandBinding? = null,
    val presentationModelId: String? = null,
    val railPreset: SignalWandRailPreset? = null
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

/** Item-bound copied values, not a reference to a model's current preset table. */
internal data class SignalWandRailPreset(
    val modelId: String,
    val name: String,
    val adjustment: ModelPose,
    val followRailTilt: Boolean
) {
    init {
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(modelId)) { "preset modelId must be namespaced" }
        require(name.isNotBlank()) { "preset name must not be blank" }
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
    val entryBoundaryId: String?,
    val indicatorId: String? = null,
    val indicatorRevision: Long? = null
) : SignalWandBinding {
    init {
        require(entryBoundaryId == null || SIMPLE_SIGNALS_NAMESPACED_ID.matches(entryBoundaryId)) { "entry boundary must be namespaced" }
        require(entryBoundaryId != null || indicatorId != null) { "a new indicator requires an entry" }
        require((indicatorId == null) == (indicatorRevision == null)) { "indicator identity requires both ID and revision" }
        require(indicatorId == null || SIMPLE_SIGNALS_NAMESPACED_ID.matches(indicatorId)) { "indicatorId must be namespaced" }
        require(indicatorRevision == null || indicatorRevision >= 0L) { "indicator revision must be non-negative" }
    }
}

private fun SignalWandBinding.description(locale: java.util.Locale = java.util.Locale.US): String = when (this) {
    is FixedBlockSignalWandBinding -> WandI18n.format(locale, "binding.block", authoringName(blockId, "name.block"), entryDirection)
    is ProtectionCurrentEntrySignalWandBinding -> WandI18n.format(locale, "binding.current", authoringName(entryBoundaryId, "name.entrance"))
    is ProtectionFixedRouteSignalWandBinding -> WandI18n.format(locale, "binding.route", authoringName(entryBoundaryId, "name.entrance"), authoringName(routeId, "name.route"))
    is AttachedSignalWandBinding -> WandI18n.format(locale, "binding.attached", authoringName(primarySignalId, "name.signal"))
    is RouteIndicatorSignalWandBinding -> WandI18n.format(locale, "binding.indicator", entryBoundaryId?.let { authoringName(it, "name.entrance") } ?: WandText.key("none"))
}

@Serializable
private data class PersistedSignalWandState(
    val itemId: String,
    val stateRevision: Long,
    val mode: String,
    val controlKind: String,
    val signalType: String,
    val binding: PersistedSignalWandBinding? = null,
    val presentationModelId: String? = null,
    val railPreset: PersistedSignalWandRailPreset? = null
)

@Serializable
private data class PersistedSignalWandRailPreset(
    val modelId: String,
    val name: String,
    val x: Double, val y: Double, val z: Double,
    val rotationX: Double, val rotationY: Double, val rotationZ: Double, val rotationW: Double,
    val followRailTilt: Boolean
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
    val entryBoundaryId: String?,
    val indicatorId: String?,
    val indicatorRevision: Long?
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
    data class RailPreset(val modelId: String, val name: String) : SignalWandSettingAction
    data object ClearRailPreset : SignalWandSettingAction
    data object RebindSignal : SignalWandSettingAction
    data object EditIndicator : SignalWandSettingAction
    data object EditPlacement : SignalWandSettingAction
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
