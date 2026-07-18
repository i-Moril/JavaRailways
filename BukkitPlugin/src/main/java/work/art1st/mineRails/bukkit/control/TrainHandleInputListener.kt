package work.art1st.mineRails.bukkit.control

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInputEvent
import work.art1st.mineRails.bukkit.BukkitPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TrainHandleInputListener(
    private val controlManager: TrainControlManager
) : Listener {
    private val states = ConcurrentHashMap<UUID, InputState>()
    private var repeatTask: ScheduledTask? = null
    private var ticks: Long = 0L
    private lateinit var plugin: BukkitPlugin

    fun start(plugin: BukkitPlugin) {
        this.plugin = plugin
        plugin.server.pluginManager.registerEvents(this, plugin)
        repeatTask = plugin.platformDispatcher.runGlobalAtFixedRate(1L, 1L, ::scheduleHeldInputTick)
    }

    fun stop() {
        repeatTask?.cancel()
        repeatTask = null
        states.clear()
        ticks = 0L
    }

    @EventHandler
    fun onPlayerInput(event: PlayerInputEvent) {
        val player = event.player
        val previous = states[player.uniqueId] ?: InputState()
        val direction = when {
            event.input.isRight -> HandleDirection.CENTER
            event.input.isForward && !event.input.isBackward -> HandleDirection.UP
            event.input.isBackward && !event.input.isForward -> HandleDirection.DOWN
            else -> HandleDirection.NONE
        }

        if (direction != previous.direction) {
            applyDirection(player, direction)
            states[player.uniqueId] = InputState(direction = direction, heldTicks = 0)
        } else {
            states[player.uniqueId] = previous.copy(direction = direction)
        }
    }

    /** Global task only selects entities; all player/controller access runs on the player scheduler. */
    private fun scheduleHeldInputTick() {
        ticks++
        val updateActionBars = ticks % ACTION_BAR_INTERVAL_TICKS == 0L
        val playerIds = HashSet(states.keys)
        if (updateActionBars) {
            plugin.server.onlinePlayers.forEach { playerIds += it.uniqueId }
        }
        playerIds.forEach { playerId ->
            val player = plugin.server.getPlayer(playerId)
            if (player == null) {
                states.remove(playerId)
                return@forEach
            }
            plugin.platformDispatcher.runForEntity(player, action = {
                tickHeldInput(player)
                if (updateActionBars) updateDriverActionBar(player)
            })
        }
    }

    private fun tickHeldInput(player: Player) {
        val state = states[player.uniqueId] ?: return
        if (state.direction == HandleDirection.NONE || state.direction == HandleDirection.CENTER) return
        if (!player.isOnline) {
            states.remove(player.uniqueId, state)
            return
        }
        val controller = controlManager.controllerFor(player)
        if (controller == null) {
            states.remove(player.uniqueId, state)
            return
        }

        val heldTicks = state.heldTicks + 1
        if (heldTicks >= INITIAL_REPEAT_DELAY_TICKS &&
            (heldTicks - INITIAL_REPEAT_DELAY_TICKS) % REPEAT_INTERVAL_TICKS == 0
        ) {
            applyDirection(player, state.direction)
        }
        state.heldTicks = heldTicks
    }

    private fun applyDirection(player: Player, direction: HandleDirection) {
        val controller = controlManager.controllerFor(player) ?: return
        when (direction) {
            HandleDirection.UP -> controller.shiftHandleTowardBrake()
            HandleDirection.DOWN -> controller.shiftHandleTowardPower()
            HandleDirection.CENTER -> controller.coast()
            HandleDirection.NONE -> return
        }
        sendControllerActionBar(player, controller)
    }

    private fun updateDriverActionBar(player: Player) {
        val controller = controlManager.controllerFor(player) ?: return
        sendControllerActionBar(player, controller)
    }

    private fun sendControllerActionBar(player: Player, controller: RealisticManualController) {
        val speedMps = controller.displaySpeed
        val speedKmh = speedMps * 3.6
        player.sendActionBar(Component.text(
            "方向: ${controller.direction.label}  挡位: ${controller.handle}  速度: ${"%.1f".format(speedKmh)} km/h (${"%.2f".format(speedMps)} m/s)"
        ))
    }

    private data class InputState(
        val direction: HandleDirection = HandleDirection.NONE,
        @Volatile var heldTicks: Int = 0
    )

    private enum class HandleDirection {
        NONE,
        UP,
        DOWN,
        CENTER
    }

    companion object {
        private const val INITIAL_REPEAT_DELAY_TICKS = 8
        private const val REPEAT_INTERVAL_TICKS = 4
        private const val ACTION_BAR_INTERVAL_TICKS = 5L
    }
}
