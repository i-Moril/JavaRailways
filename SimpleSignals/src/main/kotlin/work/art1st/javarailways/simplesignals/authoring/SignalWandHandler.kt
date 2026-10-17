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
    private val checkOverlay: (Player) -> Unit = {},
    private val showPage: (Player, SignalWandDialog) -> Unit = WandDialogs::page,
    private val findDevice: (Player) -> CompletionStage<Optional<String>> = { player -> pickSignalDevice(plugin, player, worker) }
) : Listener, CommandExecutor {
    private val sessions = SignalWandSessions()
    private val statusTasks = ConcurrentHashMap<UUID, SignalWandStatusRegistration>()
    private val choiceTokens = ConcurrentHashMap<UUID, Any>()
    private val closed = AtomicBoolean(false)
    private data class PendingRebind(val state: SignalWandState, val capture: SignalWandCapture, val device: PlacedSignalChoice)
    private val pendingRebind = ConcurrentHashMap<UUID, PendingRebind>()

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
            if (event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK) {
                if (pendingRebind.containsKey(player.uniqueId)) rebindAt(player, state)
                else if (state.binding == null) contextualClick(player, state, groundPlacement)
                else click(player, state, event.action, groundPlacement)
            } else click(player, state, event.action, groundPlacement)
        }
    }

    internal fun openSettings(player: Player, openingState: SignalWandState) {
        if (!canUse(player) || toolInHand(player) != openingState) return
        checkOverlay(player)
        sessions.invalidate(player.uniqueId)
        pendingRebind.remove(player.uniqueId)
        val worldId = player.world.uid
        val locale = player.locale()
        var draft = openingState
        fun text(key: String, vararg values: Any?) = WandI18n.format(locale, key, *values)
        fun current() = canUse(player) && player.isOnline && player.world.uid == worldId && toolInHand(player) == openingState
        val available = currentTypes() ?: return
        val presetNames = (available.flatMap { backend.railPlacementModel(SignalWandControlKind.FIXED_BLOCK,
            it.signalType, null)?.presets.orEmpty().keys } + backend.availableModelIds().orEmpty().flatMap {
            backend.railPlacementModel(SignalWandControlKind.ATTACHED_SIGNAL, openingState.signalType, it)?.presets.orEmpty().keys
        }).distinct().sorted()
        fun page(dialog: SignalWandDialog) {
            val token = Any()
            choiceTokens[player.uniqueId] = token
            fun guarded(option: Pair<String, () -> Unit>) = option.first to {
                entity(player) {
                    if (current() && choiceTokens.remove(player.uniqueId, token)) option.second()
                }
            }
            showPage(player, dialog.copy(options = dialog.options.map(::guarded), exit = dialog.exit?.let(::guarded)))
        }
        lateinit var home: () -> Unit
        fun defaults() {
            page(SignalWandDialog(text("signal.settings.default"), options = available.map { type ->
                type.displayName to { draft = draft.copy(blockDefaultType = type.signalType); home() }
            } + listOf(text("signal.settings.clear") to { draft = draft.copy(blockDefaultType = null); home() },
                text("back") to { home() })))
        }
        home = {
            if (current()) page(SignalWandDialog(text("signal.settings"), listOf(
                text("signal.settings.default.value", available.singleOrNull { it.signalType == draft.blockDefaultType }?.displayName ?: text("none")),
                text("signal.settings.preset.help")), listOf(
                text("signal.settings.default") to { defaults() },
                text("signal.preset", draft.railPresetName ?: text("signal.settings.origin")) to {
                    val choices = listOf<String?>(null) + presetNames
                    draft = draft.copy(railPresetName = choices[(choices.indexOf(draft.railPresetName) + 1).mod(choices.size)], railPreset = null)
                    home()
                }, text("apply") to {
                    if (draft.blockDefaultType == null || currentTypes()?.any { it.signalType == draft.blockDefaultType } == true) {
                        val replacement = draft.copy(stateRevision = Math.addExact(openingState.stateRevision, 1), binding = null)
                        if (replaceToolState(player, openingState, replacement)) refreshStatus(player)
                    }
                }, text("cancel") to { })))
        }
        home()
    }

    internal fun switchMode(player: Player) {
        if (!canUse(player)) return
        val state = toolInHand(player) ?: return
        choiceTokens.remove(player.uniqueId)
        pendingRebind.remove(player.uniqueId)
        sessions.invalidate(player.uniqueId)
        val next = state.copy(stateRevision = Math.addExact(state.stateRevision, 1), binding = null,
            mode = if (state.mode == SignalWandMode.PLACEMENT) SignalWandMode.DELETION else SignalWandMode.PLACEMENT)
        if (replaceToolState(player, state, next)) refreshStatus(player)
    }

    private fun openIndicatorEditor(player: Player, state: SignalWandState,
        creationCapture: SignalWandCapture, binding: RouteIndicatorSignalWandBinding,
        placement: ModelPlacement? = null) {
        if (!canUse(player) || !player.isOnline || toolInHand(player) != state || player.world.uid != creationCapture.worldId) return
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
            val guarded = options.map { (label, action) -> label to {
                entity(player) {
                    if (ownerCurrent() && used.compareAndSet(false, true) && session.busy.compareAndSet(false, true))
                        work(action)
                }
            } }
            if (binding.indicatorId == null && placement != null) {
                showPage(player, SignalWandDialog(title, options = guarded.dropLast(1),
                    exit = guarded.last().let { WandI18n.format(locale, "signal.creation.cancel") to it.second }))
            } else showAuthoringDialog(player, title, guarded)
        }
        work {
            val capture = creationCapture
            require(backend.current(capture)) { WandI18n.format(locale, "signal.selection.expired") }
            val editor = RouteIndicatorWandEditor(backend, capture, ::page, ::reply, ::active, locale) { result ->
                entity(player) {
                    if (ownerCurrent()) {
                        player.sendMessage(Component.text(result.text.render(locale).take(MESSAGE_LIMIT)))
                        if (result.applied && state.binding != null) {
                            val replacement = state.copy(stateRevision = Math.addExact(state.stateRevision, 1), binding = null)
                            if (replaceToolState(player, state, replacement)) refreshStatus(player)
                        }
                    }
                    choiceTokens.remove(playerId, token)
                    session.busy.set(false)
                }
            }
            val entry = requireNotNull(binding.entryBoundaryId) { WandI18n.format(locale, "signal.rebind.target.empty") }
            editor.configure(binding, entry, placement)
        }
    }

    internal fun openPlacementEditor(player: Player, state: SignalWandState, selected: PlacedSignalChoice) {
        if (!canUse(player) || !player.isOnline || toolInHand(player) != state) return
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
            val capture = backend.capture(worldId, null)
            val editor = PlacementWandEditor(backend, capture, ::page, ::input, ::reply, ::active, locale)
            editor.editDevice(selected)
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
            } else {
                val result = backend.place(captured, binding, placement, ::active)
                entity(player) {
                    if (ownerCurrent()) {
                        player.sendMessage(Component.text(result.text.render(locale).take(MESSAGE_LIMIT)))
                        if (result.applied) {
                            val replacement = state.copy(stateRevision = Math.addExact(state.stateRevision, 1), binding = null)
                            if (replaceToolState(player, state, replacement)) {
                                sessions.invalidate(playerId, session)
                                refreshStatus(player)
                            }
                        }
                    }
                    session.busy.set(false)
                }
            }
        }
        fun completeBindingOnOwner(binding: SignalWandBinding, description: String) {
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
                WandI18n.format(locale, "signal.bound.hint", description)
            ))
        }
        fun completeBinding(binding: SignalWandBinding) {
            val description = binding.description(locale) { backend.boundaryLabel(captured, it) }
            entity(player) { completeBindingOnOwner(binding, description) }
        }
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

    /** Shared scheduling for local menus; every callback retains the exact item/world and page. */
    private fun authoringFlow(player: Player, state: SignalWandState,
        prepare: (SignalWandCapture, (SignalWandDialog) -> Unit, (String) -> Unit, () -> Boolean) -> Unit) {
        if (!canUse(player) || toolInHand(player) != state) return
        val worldId = player.world.uid
        val playerId = player.uniqueId
        sessions.invalidate(playerId)
        val token = Any()
        choiceTokens[playerId] = token
        val pageToken = java.util.concurrent.atomic.AtomicReference<Any>()
        fun active() = operational() && choiceTokens[playerId] === token
        fun ownerCurrent() = active() && player.isOnline && player.world.uid == worldId &&
            player.hasPermission(PERMISSION) && toolInHand(player) == state
        fun reply(message: String) = entity(player) {
            if (ownerCurrent()) player.sendMessage(Component.text(message.take(MESSAGE_LIMIT)))
            choiceTokens.remove(playerId, token)
        }
        fun work(action: () -> Unit) {
            try { worker {
                if (active()) try { action() } catch (failure: Exception) {
                    reply("SimpleSignals: ${failure.message ?: failure.javaClass.simpleName}")
                }
            } } catch (failure: Exception) { reply("SimpleSignals: ${failure.message ?: failure.javaClass.simpleName}") }
        }
        fun page(dialog: SignalWandDialog) = entity(player) {
            if (ownerCurrent()) {
                val generation = Any()
                pageToken.set(generation)
                val used = AtomicBoolean(false)
                fun guard(option: Pair<String, () -> Unit>) = option.first to {
                    entity(player) {
                        if (ownerCurrent() && pageToken.get() === generation && used.compareAndSet(false, true)) work(option.second)
                    }
                }
                showPage(player, dialog.copy(options = dialog.options.map(::guard), exit = dialog.exit?.let(::guard)))
            }
        }
        work { prepare(backend.capture(worldId, null), ::page, ::reply, ::active) }
    }

    private fun chooseCreation(player: Player, state: SignalWandState, capture: SignalWandCapture,
        point: RailPositionSnapshot, ground: ModelPlacement.Ground?, page: (SignalWandDialog) -> Unit,
        reply: (String) -> Unit, active: () -> Boolean, locale: java.util.Locale) {
        if (!backend.protectionEntrance(capture, point)) {
            beginCreation(player, state, capture, point, ground, SignalWandControlKind.FIXED_BLOCK, null, page, reply, active, locale)
            return
        }
        val kinds = listOf(SignalWandControlKind.PROTECTION_CURRENT_ENTRY, SignalWandControlKind.PROTECTION_FIXED_ROUTE,
            SignalWandControlKind.ROUTE_INDICATOR)
        page(SignalWandDialog(WandI18n.format(locale, "signal.creation.purpose"), options = kinds.map { kind ->
            WandI18n.format(locale, "control.${kind.name}") to {
                beginCreation(player, state, capture, point, ground, kind, null, page, reply, active, locale)
            }
        }, exit = WandI18n.format(locale, "signal.creation.cancel") to { reply(WandI18n.format(locale, "signal.editor.closed")) }, columns = 1))
    }

    private fun beginCreation(player: Player, state: SignalWandState, capture: SignalWandCapture,
        point: RailPositionSnapshot?, ground: ModelPlacement.Ground?, kind: SignalWandControlKind,
        existingBinding: SignalWandBinding?, page: (SignalWandDialog) -> Unit, reply: (String) -> Unit,
        active: () -> Boolean, locale: java.util.Locale) {
        fun text(key: String, vararg values: Any?) = WandI18n.format(locale, key, *values)
        val modelOnly = kind == SignalWandControlKind.ATTACHED_SIGNAL || kind == SignalWandControlKind.ROUTE_INDICATOR
        fun selected(id: String) {
            require(active() && backend.current(capture)) { text("editor.selection.expired") }
            val typed = backend.capture(capture.worldId, if (modelOnly) null else id, if (modelOnly) id else null)
            val model = backend.railPlacementModel(kind, if (modelOnly) state.signalType else id, if (modelOnly) id else null)
            val preset = state.railPresetName?.let { name ->
                val value = requireNotNull(model?.presets?.get(name)) { text("signal.preset.missing", name) }
                SignalWandRailPreset(checkNotNull(model).modelId, name, value.adjustment, checkNotNull(value.followRailTilt))
            }
            val choices = existingBinding?.let { listOf(SignalWandChoice(it, WandText.key("signal.local.create"))) }
                ?: backend.resolveEntrance(typed, kind, SignalWandMode.PLACEMENT, checkNotNull(point)).filter { choice ->
                    when (val binding = choice.binding) {
                        is ProtectionCurrentEntrySignalWandBinding -> binding.intent === NewProtectionSignal
                        is ProtectionFixedRouteSignalWandBinding -> binding.intent === NewProtectionSignal
                        is RouteIndicatorSignalWandBinding -> binding.indicatorId == null
                        else -> true
                    }
                }
            require(choices.isNotEmpty()) { text("signal.creation.no_route") }
            fun place(binding: SignalWandBinding) {
                require(active() && backend.current(typed)) { text("editor.selection.expired") }
                require(binding !is FixedBlockSignalWandBinding || !backend.occupiedPrimary(typed, binding)) {
                    text("signal.creation.occupied") }
                if (point == null) {
                    entity(player) {
                        if (canUse(player) && player.isOnline && player.world.uid == typed.worldId && toolInHand(player) == state && active()) {
                            val replacement = state.copy(stateRevision = Math.addExact(state.stateRevision, 1), controlKind = kind,
                                signalType = if (modelOnly) state.signalType else id, presentationModelId = if (modelOnly) id else null,
                                railPreset = preset, binding = binding)
                            if (replaceToolState(player, state, replacement)) {
                                choiceTokens.remove(player.uniqueId)
                                refreshStatus(player)
                                player.sendMessage(Component.text(text("signal.bound.hint", text("signal.local.create"))))
                            }
                        }
                    }
                } else {
                    val placement = ground ?: ModelPlacement.RailAttached(point).let { base -> preset?.let {
                        base.applyPreset(RailPlacementPreset(it.adjustment, it.followRailTilt)) } ?: base }
                    if (binding is RouteIndicatorSignalWandBinding) entity(player) {
                        if (canUse(player) && player.isOnline && player.world.uid == typed.worldId && toolInHand(player) == state && active())
                            openIndicatorEditor(player, state, typed, binding, placement)
                    } else reply(backend.place(typed, binding, placement, active).text.render(locale))
                }
            }
            if (choices.size == 1) place(choices.single().binding)
            else page(SignalWandDialog(text("signal.creation.target"), options = choices.map { choice ->
                choice.label.render(locale) to { place(choice.binding) }
            }, exit = text("signal.creation.cancel") to { reply(text("signal.editor.closed")) }, columns = 1))
        }
        val default = state.blockDefaultType?.takeIf { kind == SignalWandControlKind.FIXED_BLOCK &&
            backend.availableSignalTypes().orEmpty().any { type -> type.signalType == it } }
        if (default != null) { selected(default); return }
        val types = if (modelOnly) backend.availableModelIds().orEmpty().map { it to it }
            else backend.availableSignalTypes().orEmpty().map { it.signalType to it.displayName }
        page(SignalWandDialog(text(if (modelOnly) "signal.device.model.change" else "signal.device.type.change"),
            listOf(text("signal.types.validation")), types.map { (id, label) -> label to { selected(id) } },
            text("signal.creation.cancel") to { reply(text("signal.editor.closed")) }))
    }

    internal fun openLocalEditor(player: Player, state: SignalWandState, device: PlacedSignalChoice) {
        val locale = player.locale()
        authoringFlow(player, state) { capture, page, reply, active ->
            if (state.mode == SignalWandMode.DELETION) {
                page(SignalWandDialog(WandI18n.format(locale, "signal.device.delete.confirm"),
                    listOf(authoringName(device.id, "name.signal").render(locale)), listOf(
                        WandI18n.format(locale, "apply") to { reply(backend.deletePlaced(capture, device, active).text.render(locale)) },
                        WandI18n.format(locale, "cancel") to { reply(WandI18n.format(locale, "signal.editor.closed")) })))
                return@authoringFlow
            }
            SignalDeviceEditor(backend, capture, device, page, reply, active,
                placement = { entity(player) { if (active()) openPlacementEditor(player, state, device) } },
                rebind = {
                    entity(player) {
                        if (active()) {
                            pendingRebind[player.uniqueId] = PendingRebind(state, capture, device)
                            choiceTokens.remove(player.uniqueId)
                            player.sendMessage(Component.text(WandI18n.format(locale, "signal.rebind.click")))
                        }
                    }
                }, indicator = {
                    val definition = backend.indicatorDevices(capture).single { it.indicatorId == device.id }
                    require(definition.entryBoundaryId != null) { WandI18n.format(locale, "signal.rebind.click") }
                    entity(player) { if (active()) openIndicatorEditor(player, state, capture,
                        RouteIndicatorSignalWandBinding(capture.worldId, definition.entryBoundaryId, definition.indicatorId, definition.revision)) }
                }, attached = {
                    beginCreation(player, state, capture, null, null, SignalWandControlKind.ATTACHED_SIGNAL,
                        AttachedSignalWandBinding(capture.worldId, device.id, device.revision, NewAttachedSignal), page, reply, active, locale)
                }, locale = locale).show()
        }
    }

    private fun contextualClick(player: Player, state: SignalWandState, ground: ModelPlacement.Ground?) {
        val worldId = player.world.uid
        val sight = player.eyeLocation.direction
        val token = Any()
        choiceTokens[player.uniqueId] = token
        fun current() = canUse(player) && player.isOnline && player.world.uid == worldId && toolInHand(player) == state &&
            choiceTokens[player.uniqueId] === token
        findDevice(player).whenComplete { picked, pickFailure -> entity(player) {
            if (!current()) return@entity
            if (pickFailure != null) {
                choiceTokens.remove(player.uniqueId, token)
                player.sendMessage(Component.text("SimpleSignals: ${pickFailure.message}")); return@entity
            }
            if (picked?.isPresent == true) {
                val objectId = picked.get()
                authoringFlow(player, state) { capture, _, _, active ->
                    val device = backend.placedDevices(capture).singleOrNull { it.renderObjectId() == objectId }
                        ?: error("The picked signal no longer exists")
                    entity(player) { if (active()) openLocalEditor(player, state, device) }
                }
            } else findTarget(player).whenComplete { target, failure -> entity(player) targetOwner@{
                if (!current()) return@targetOwner
                if (failure != null || target?.isPresent != true) {
                    choiceTokens.remove(player.uniqueId, token)
                    player.sendMessage(Component.text(WandI18n.format(player, "rail.none"))); return@targetOwner
                }
                val selected = target.get()
                val locale = player.locale()
                authoringFlow(player, state) { capture, page, reply, active ->
                    val curve: Curve = targets.findCurve(worldId, selected.rail().id).orElseThrow()
                    val tangent = curve.getPointAt(selected.arcLength()).tangent
                    val dot = tangent.x() * sight.x + tangent.y() * sight.y + tangent.z() * sight.z
                    val point = RailPositionSnapshot(selected.rail().id, if (dot >= 0) ViewDirection.AB else ViewDirection.BA, selected.arcLength())
                    val local = backend.placedDevices(capture).filter { device ->
                        (device.placement as? ModelPlacement.RailAttached)?.position?.let {
                            it.railId == point.railId && abs(it.arcLengthInAbMeters - point.arcLengthInAbMeters) <= 0.75
                        } == true
                    }
                    fun create() {
                        require(abs(dot) >= 0.15) { WandI18n.format(locale, "signal.direction") }
                        chooseCreation(player, state, capture, point, ground, page, reply, active, locale)
                    }
                    if (local.isEmpty() && state.mode == SignalWandMode.DELETION) reply(WandI18n.format(locale, "signal.local.empty"))
                    else if (local.isEmpty()) create()
                    else page(SignalWandDialog(WandI18n.format(locale, "signal.local.choose"), options = local.mapIndexed { index, device ->
                        WandI18n.format(locale, "choice.numbered", index + 1, authoringName(device.id, "name.signal")) to {
                            entity(player) { if (active()) openLocalEditor(player, state, device) }
                        }
                    } + if (state.mode == SignalWandMode.PLACEMENT) listOf(WandI18n.format(locale, "signal.local.create") to ::create) else emptyList(),
                        exit = WandI18n.format(locale, "cancel") to { reply(WandI18n.format(locale, "signal.editor.closed")) }))
                }
            } }
        } }
    }

    private fun rebindAt(player: Player, state: SignalWandState) {
        val pending = pendingRebind.remove(player.uniqueId) ?: return
        val locale = player.locale()
        val worldId = player.world.uid
        if (pending.state != state || pending.capture.worldId != worldId || !canUse(player)) return
        val sight = player.eyeLocation.direction
        val token = Any()
        choiceTokens[player.uniqueId] = token
        findTarget(player).whenComplete { target, failure -> entity(player) {
            if (!canUse(player) || !player.isOnline || player.world.uid != worldId || toolInHand(player) != state ||
                choiceTokens[player.uniqueId] !== token) return@entity
            if (failure != null || target?.isPresent != true) {
                player.sendMessage(Component.text(WandI18n.format(locale, "rail.none"))); return@entity
            }
            val selected = target.get()
            authoringFlow(player, state) { capture, page, reply, active ->
                require(backend.current(pending.capture) && pending.device in backend.placedDevices(capture)) {
                    WandI18n.format(locale, "editor.selection.expired") }
                val curve: Curve = targets.findCurve(worldId, selected.rail().id).orElseThrow()
                val tangent = curve.getPointAt(selected.arcLength()).tangent
                val dot = tangent.x() * sight.x + tangent.y() * sight.y + tangent.z() * sight.z
                require(abs(dot) >= 0.15) { WandI18n.format(locale, "signal.direction") }
                val point = RailPositionSnapshot(selected.rail().id, if (dot >= 0) ViewDirection.AB else ViewDirection.BA, selected.arcLength())
                if (pending.device.kind == PlacedSignalKind.INDICATOR) {
                    val definition = backend.indicatorDevices(capture).single { it.indicatorId == pending.device.id }
                    val entries = backend.resolveEntrance(capture, SignalWandControlKind.ROUTE_INDICATOR, SignalWandMode.PLACEMENT, point)
                        .map { (it.binding as RouteIndicatorSignalWandBinding).entryBoundaryId }.distinct()
                    require(entries.size == 1) { WandI18n.format(locale, "signal.rebind.target.empty") }
                    val entry = checkNotNull(entries.single())
                    val typed = backend.capture(worldId, null, definition.model?.modelId)
                    entity(player) { if (active() && canUse(player) && toolInHand(player) == state)
                        openIndicatorEditor(player, state, typed, RouteIndicatorSignalWandBinding(worldId, entry,
                            definition.indicatorId, definition.revision)) }
                } else {
                    val device = backend.rebindDevices(capture).single { it.signalId == pending.device.id }
                    val localTargets = backend.resolveEntrance(capture, device.controlKind, SignalWandMode.PLACEMENT, point)
                        .map { choice -> when (val binding = choice.binding) {
                            is FixedBlockSignalWandBinding -> FixedBlockSignalRebindTarget(work.art1st.javarailways.simplesignals.signal.FixedBlockSignalEntry(binding.blockId, binding.entryDirection))
                            is ProtectionCurrentEntrySignalWandBinding -> ProtectionSignalRebindTarget(binding.entryBoundaryId, binding.entryBoundaryId)
                            is ProtectionFixedRouteSignalWandBinding -> ProtectionSignalRebindTarget(binding.entryBoundaryId, binding.routeId)
                            else -> error("This device does not support entrance rebinding")
                        } }.distinct().mapNotNull { local -> backend.rebindTargets(capture, device).singleOrNull { target ->
                            when {
                                local is ProtectionSignalRebindTarget && target is ProtectionSignalRebindTarget ->
                                    local.entryBoundaryId == target.entryBoundaryId && local.bindingId == target.bindingId
                                else -> local == target
                            }
                        } }
                    require(localTargets.isNotEmpty()) { WandI18n.format(locale, "signal.rebind.target.empty") }
                    fun confirm(target: SignalRebindTarget) {
                        page(SignalWandDialog(WandI18n.format(locale, "signal.rebind.confirm", authoringName(device.signalId, "name.signal")),
                            listOf(WandI18n.format(locale, "signal.device.preserve")), listOf(
                                WandI18n.format(locale, "apply") to { reply(backend.rebind(capture, device, target, active).text.render(locale)) },
                                WandI18n.format(locale, "cancel") to { reply(WandI18n.format(locale, "signal.editor.closed")) })))
                    }
                    if (localTargets.size == 1) confirm(localTargets.single())
                    else page(SignalWandDialog(WandI18n.format(locale, "signal.creation.target"), options = localTargets.map { target ->
                        target.description.render(locale) to { confirm(target) }
                    }, exit = WandI18n.format(locale, "cancel") to { reply(WandI18n.format(locale, "signal.editor.closed")) }, columns = 1))
                }
            }
        } }
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
        pendingRebind.remove(event.player.uniqueId)
        choiceTokens.remove(event.player.uniqueId)
        sessions.invalidate(event.player.uniqueId)
        stopStatus(event.player.uniqueId)
    }
    @EventHandler fun onWorldChange(event: PlayerChangedWorldEvent) {
        pendingRebind.remove(event.player.uniqueId)
        choiceTokens.remove(event.player.uniqueId)
        sessions.invalidate(event.player.uniqueId)
        deferRefresh(event.player)
    }
    @EventHandler(ignoreCancelled = true) fun onHeldItem(event: PlayerItemHeldEvent) {
        pendingRebind.remove(event.player.uniqueId)
        choiceTokens.remove(event.player.uniqueId)
        sessions.invalidate(event.player.uniqueId)
        deferRefresh(event.player)
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSwapHands(event: PlayerSwapHandItemsEvent) {
        work.art1st.javarailways.bukkit.api.wand.WandModeSwitch.handle(event,
            { readTool(plugin, it) != null }) { player ->
            switchMode(player)
            checkOverlay(player)
        }
    }
    @EventHandler(ignoreCancelled = true) fun onInventoryClick(event: InventoryClickEvent) {
        pendingRebind.remove(event.whoClicked.uniqueId)
        choiceTokens.remove(event.whoClicked.uniqueId)
        sessions.invalidate(event.whoClicked.uniqueId)
        (event.whoClicked as? Player)?.let(::deferRefresh)
    }
    @EventHandler(ignoreCancelled = true) fun onInventoryDrag(event: InventoryDragEvent) {
        pendingRebind.remove(event.whoClicked.uniqueId)
        choiceTokens.remove(event.whoClicked.uniqueId)
        sessions.invalidate(event.whoClicked.uniqueId)
        (event.whoClicked as? Player)?.let(::deferRefresh)
    }
    @EventHandler(ignoreCancelled = true) fun onDrop(event: PlayerDropItemEvent) {
        pendingRebind.remove(event.player.uniqueId)
        choiceTokens.remove(event.player.uniqueId)
        sessions.invalidate(event.player.uniqueId)
        deferRefresh(event.player)
    }
    @EventHandler(ignoreCancelled = true) fun onPickup(event: EntityPickupItemEvent) {
        (event.entity as? Player)?.let(::deferRefresh)
    }
    @EventHandler(ignoreCancelled = true) fun onWorldUnload(event: WorldUnloadEvent) {
        pendingRebind.entries.removeIf { it.value.capture.worldId == event.world.uid }
        sessions.invalidateWorld(event.world.uid)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        sessions.close()
        pendingRebind.clear()
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
                }, state.blockDefaultType, state.railPresetName
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
                        preset.rotationW), preset.followRailTilt) }, persisted.blockDefaultType, persisted.railPresetName
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
            val type = available?.singleOrNull { it.signalType == state.blockDefaultType }?.displayName
                ?: WandI18n.format(locale, "none")
            return Component.text(WandI18n.format(locale, "signal.status.current",
                WandI18n.format(locale, "mode." + state.mode.label),
                state.railPresetName ?: WandI18n.format(locale, "signal.settings.origin"), type))
        }

        internal fun decorate(meta: org.bukkit.inventory.meta.ItemMeta, state: SignalWandState, locale: java.util.Locale = java.util.Locale.US) {
            meta.displayName(Component.text(WandI18n.format(locale, "signal.name")))
            meta.lore(listOf(
                Component.text(WandI18n.format(locale, "signal.lore")),
                Component.text(WandI18n.format(locale, "signal.mode", WandI18n.format(locale, "mode." + state.mode.label))),
                Component.text(WandI18n.format(locale, "signal.preset", state.railPresetName ?: WandI18n.format(locale, "signal.settings.origin")))
            ))
            meta.setEnchantmentGlintOverride(state.binding != null)
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
    val railPreset: SignalWandRailPreset? = null,
    val blockDefaultType: String? = null,
    val railPresetName: String? = null
) {
    init {
        require(stateRevision >= 0L) { "stateRevision must be non-negative" }
        require(SIMPLE_SIGNALS_NAMESPACED_ID.matches(signalType)) { "signalType must be namespaced" }
        require(blockDefaultType == null || SIMPLE_SIGNALS_NAMESPACED_ID.matches(blockDefaultType)) { "default type must be namespaced" }
        require(railPresetName == null || railPresetName.isNotBlank()) { "preset name must not be blank" }
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

private fun SignalWandBinding.description(locale: java.util.Locale = java.util.Locale.US,
    boundaryLabel: (String) -> WandText = { authoringName(it, "name.entrance") }): String = when (this) {
    is FixedBlockSignalWandBinding -> WandI18n.format(locale, "binding.block", authoringName(blockId, "name.block"), entryDirection)
    is ProtectionCurrentEntrySignalWandBinding -> WandI18n.format(locale, "binding.current", boundaryLabel(entryBoundaryId))
    is ProtectionFixedRouteSignalWandBinding -> WandI18n.format(locale, "binding.route", boundaryLabel(entryBoundaryId), authoringName(routeId, "name.route"))
    is AttachedSignalWandBinding -> WandI18n.format(locale, "binding.attached", authoringName(primarySignalId, "name.signal"))
    is RouteIndicatorSignalWandBinding -> WandI18n.format(locale, "binding.indicator", entryBoundaryId?.let(boundaryLabel) ?: WandText.key("none"))
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
    val railPreset: PersistedSignalWandRailPreset? = null,
    val blockDefaultType: String? = null,
    val railPresetName: String? = null
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
