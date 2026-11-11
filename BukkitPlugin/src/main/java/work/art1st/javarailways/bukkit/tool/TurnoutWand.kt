package work.art1st.javarailways.bukkit.tool

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerInteractEvent
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.api.rail.RailTarget
import work.art1st.javarailways.bukkit.util.BukkitThreadGuardBridge
import work.art1st.javarailways.bukkit.util.HeavyRailQueries
import work.art1st.javarailways.bukkit.util.PlayerOperationGate
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import java.util.logging.Level

enum class TurnoutWandMode : WandModeSpec {
    SELECT;

    override val displayName = "Turnout Wand"
    override val description = "Select an approach, then connect a branch"
}

class TurnoutWandState(override var mode: TurnoutWandMode = TurnoutWandMode.SELECT) : WandState<TurnoutWandMode> {
    // Exact topology identity cannot survive item decoding, a world reload or a server restart.
    internal var selection: TurnoutWandSelection? = null
    internal var querySequence = 0L

    internal fun clear() {
        querySequence++
        selection = null
    }
}

@Serializable
private data class SavedTurnoutWandState(val mode: TurnoutWandMode = TurnoutWandMode.SELECT)

internal object TurnoutWandStateCodec {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    fun encode(state: TurnoutWandState): String = json.encodeToString(SavedTurnoutWandState(state.mode))

    fun decode(encoded: String): TurnoutWandState? = runCatching {
        TurnoutWandState(json.decodeFromString<SavedTurnoutWandState>(encoded).mode)
    }.getOrNull()
}

internal const val TURNOUT_WAND_PERMISSION = "javarailways.admin"

/** Shared by the command route and its tests; inventory access remains on the player owner. */
internal fun giveTurnoutWand(sender: CommandSender, give: (Player) -> Unit) {
    if (sender !is Player) {
        sender.sendMessage(TurnoutWandI18n.component(sender, "command.player_only"))
    } else if (!sender.hasPermission(TURNOUT_WAND_PERMISSION)) {
        sender.sendMessage(TurnoutWandI18n.component(sender, "permission.denied"))
    } else {
        give(sender)
        sender.sendMessage(TurnoutWandI18n.component(sender, "command.given"))
    }
}

/** One-shot local targeting; the existing infrastructure service owns every turnout mutation. */
class TurnoutWand(plugin: BukkitPlugin) : AbstractWand<TurnoutWandState, TurnoutWandMode>(
    plugin, "javarailways_turnout_wand", Material.BLAZE_ROD,
    Component.text("Turnout Wand", NamedTextColor.AQUA), emptyList(), emptyList()
) {
    private val queries = PlayerOperationGate()

    override fun createDefaultState() = TurnoutWandState()
    override fun encodeState(state: TurnoutWandState) = TurnoutWandStateCodec.encode(state)
    override fun decodeState(encoded: String) = TurnoutWandStateCodec.decode(encoded)
    override fun itemDisplayNameFor(player: Player) = TurnoutWandI18n.component(player, "item.name").color(NamedTextColor.AQUA)
    override fun itemLoreFor(player: Player) = HELP_KEYS.map { TurnoutWandI18n.component(player, it).color(NamedTextColor.GRAY) }
    override fun selectionClearedMessage(player: Player) = TurnoutWandI18n.component(player, "selection.cleared")

    override fun clearSelection(player: Player, state: TurnoutWandState) = state.clear()

    override fun onPlayerQuit(player: Player, state: TurnoutWandState) {
        state.clear()
    }

    @EventHandler
    fun onPlayerChangedWorld(event: PlayerChangedWorldEvent) {
        // Clear even while another item is held, including an A -> B -> A trip between clicks.
        // Keep the query gate until its completion so a cancelled worker cannot release a newer query.
        getState(event.player).clear()
    }

    override fun showSettings(player: Player, state: TurnoutWandState) {
        if (!permitted(player)) return
        state.querySequence++ // Opening help cancels any in-flight click.
        HELP_KEYS.forEach { player.sendMessage(TurnoutWandI18n.component(player, it)) }
        showConnection(player, state)
    }

    override fun handleWandInteract(player: Player, state: TurnoutWandState, event: PlayerInteractEvent) {
        if (!permitted(player)) return
        val selectApproach = when (event.action) {
            Action.LEFT_CLICK_AIR, Action.LEFT_CLICK_BLOCK -> true
            Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK -> false
            else -> return
        }
        val worldId = player.world.uid
        val world = plugin.worldManager.loadedRailWorld(worldId)
        if (world == null) {
            state.clear()
            message(player, "result.world_unavailable")
            return
        }
        val topology = world.topologyState
        if (!selectApproach) {
            val selection = state.selection
            if (selection == null) {
                message(player, "selection.required")
                return
            }
            if (!selection.isCurrent(worldId, topology)) {
                state.clear()
                message(player, "selection.stale")
                return
            }
        }
        if (queries.tryStart(player.uniqueId, "TurnoutWand.target") != null) {
            message(player, "query.busy")
            return
        }
        val sequence = ++state.querySequence
        try {
            plugin.railTargetService.findLookedAtRail(
                player, HeavyRailQueries.DEFAULT_TARGET_MAX_DISTANCE, HeavyRailQueries.DEFAULT_TARGET_HIT_RADIUS
            ).whenComplete { target, failure ->
                try {
                    if (!plugin.isEnabled) {
                        queries.finish(player.uniqueId)
                        return@whenComplete
                    }
                    val scheduled = plugin.platformDispatcher.runForEntity(player, action = {
                        try {
                            BukkitThreadGuardBridge.runInPlayerWorldContext(player) runInSimulationContext@ {
                                if (!plugin.isEnabled || !player.isOnline) return@runInSimulationContext
                                val activeState = if (isWandItem(player.inventory.itemInMainHand)) getState(player) else null
                                if (!permitted(player)) return@runInSimulationContext
                                val currentWorld = plugin.worldManager.loadedRailWorld(player.world.uid)
                                if (currentWorld !== world) {
                                    state.clear()
                                    message(player, "selection.stale")
                                    return@runInSimulationContext
                                }
                                when (turnoutTargetValidity(state, sequence, worldId, topology, activeState, player.world.uid,
                                    currentWorld.topologyState, target?.orElse(null)?.rail()?.id)) {
                                    TurnoutWandTargetValidity.CANCELLED -> return@runInSimulationContext
                                    TurnoutWandTargetValidity.STALE -> {
                                        state.clear()
                                        message(player, "selection.stale")
                                        return@runInSimulationContext
                                    }
                                    TurnoutWandTargetValidity.CURRENT -> Unit
                                }
                                if (failure != null) failed(player, failure)
                                else applyTarget(player, state, world, currentWorld.topologyState, target.orElse(null), selectApproach)
                                showConnection(player, state)
                            }
                        } catch (failure: Exception) {
                            failed(player, failure)
                        } finally {
                            queries.finish(player.uniqueId)
                        }
                    }, retired = { queries.finish(player.uniqueId) })
                    if (!scheduled) queries.finish(player.uniqueId)
                } catch (failure: Exception) {
                    queries.finish(player.uniqueId)
                    plugin.logger.log(Level.WARNING, "TurnoutWand completion could not reach its player owner", failure)
                }
            }
        } catch (failure: Exception) {
            queries.finish(player.uniqueId)
            failed(player, failure)
        }
    }

    private fun applyTarget(
        player: Player,
        state: TurnoutWandState,
        world: World,
        topology: TopologyState,
        target: RailTarget?,
        selectApproach: Boolean
    ) {
        if (target == null) {
            message(player, "target.missing")
            return
        }
        val rail = world.getRail(target.rail().id)
        if (rail !== target.rail()) {
            state.clear()
            message(player, "selection.stale")
            return
        }
        val port = turnoutPortAt(topology, rail.id, target.arcLength(), rail.curve.totalArcLength)
        if (port == null) {
            message(player, "target.not_turnout")
            return
        }
        if (selectApproach) {
            state.selection = TurnoutWandSelection(player.world.uid, topology, port)
            message(player, "selection.selected")
            return
        }
        val request = state.selection?.request(player.world.uid, topology, port)
        if (request == null) {
            message(player, "target.illegal")
            return
        }
        val worldId = player.world.uid
        plugin.infrastructureResourceService.selectTurnout(plugin, worldId, request).whenComplete { result, failure ->
            if (!plugin.isEnabled) return@whenComplete
            plugin.platformDispatcher.runForEntity(player, action = {
                if (!plugin.isEnabled || !player.isOnline || player.world.uid != worldId) return@runForEntity
                if (failure != null) failed(player, failure)
                else {
                    player.sendMessage(Component.text(TurnoutWandI18n.result(player.locale(), result))
                        .color(if (result.successful) NamedTextColor.GREEN else NamedTextColor.YELLOW))
                    BukkitThreadGuardBridge.runInPlayerWorldContext(player) runInSimulationContext@ { showConnection(player, state) }
                }
            })
        }
    }

    private fun showConnection(player: Player, state: TurnoutWandState) {
        val selection = state.selection ?: return
        val world = plugin.worldManager.loadedRailWorld(player.world.uid)
        if (world == null || !selection.isCurrent(player.world.uid, world.topologyState)) {
            state.clear()
            message(player, "selection.stale")
            return
        }
        val actual = currentTurnoutConnection(world, selection)
        highlight(player, world, selection.fromPort, Color.AQUA)
        if (actual == null) {
            player.sendActionBar(TurnoutWandI18n.component(player, "connection.none"))
            return
        }
        highlight(player, world, actual.toPort, if (actual.reciprocal) Color.LIME else Color.ORANGE)
        player.sendActionBar(TurnoutWandI18n.component(
            player, if (actual.reciprocal) "connection.aligned" else "connection.one_way",
            portLabel(player, world, selection.fromPort), portLabel(player, world, actual.toPort)
        ))
    }

    private fun portLabel(player: Player, world: World, port: RailEndpointRef): String {
        val curve = requireNotNull(world.getRail(port.railId)).curve
        val distance = minOf(PREVIEW_METERS, curve.totalArcLength)
        val position = curve.getPointAt(if (port.endpoint == RailEndpoint.A) distance else curve.totalArcLength - distance).position
        return TurnoutWandI18n.format(player.locale(), "connection.port",
            position.x(), position.y(), position.z(), port.railId.toString().take(8), port.endpoint)
    }

    private fun highlight(player: Player, world: World, port: RailEndpointRef, color: Color) {
        val curve = world.getRail(port.railId)?.curve ?: return
        val length = minOf(PREVIEW_METERS, curve.totalArcLength)
        repeat(9) { index ->
            val distance = length * index / 8.0
            val point = curve.getPointAt(if (port.endpoint == RailEndpoint.A) distance else curve.totalArcLength - distance).position
            player.spawnParticle(Particle.DUST, Location(player.world, point.x(), point.y() + 0.2, point.z()),
                1, 0.0, 0.0, 0.0, 0.0, Particle.DustOptions(color, 1.0f))
        }
    }

    private fun permitted(player: Player): Boolean {
        if (player.hasPermission(TURNOUT_WAND_PERMISSION)) return true
        message(player, "permission.denied")
        return false
    }

    private fun message(player: Player, key: String) = player.sendMessage(TurnoutWandI18n.component(player, key))

    private fun failed(player: Player, failure: Throwable) {
        plugin.logger.log(Level.WARNING, "TurnoutWand operation failed for ${player.uniqueId}", failure)
        message(player, "query.failed")
    }

    private companion object {
        const val PREVIEW_METERS = 5.0
        val HELP_KEYS = listOf("help.select", "help.inspect", "help.preview", "help.authority")
    }
}
