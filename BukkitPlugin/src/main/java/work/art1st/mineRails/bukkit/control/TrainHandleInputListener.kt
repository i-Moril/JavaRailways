package work.art1st.mineRails.bukkit.control

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInputEvent
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import work.art1st.mineRails.bukkit.command.TrainCommand
import java.util.UUID

class TrainHandleInputListener : Listener {
    private val states = mutableMapOf<UUID, InputState>()
    private var repeatTask: BukkitTask? = null

    fun start(plugin: Plugin) {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        repeatTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tickHeldInputs() }, 1L, 1L)
    }

    fun stop() {
        repeatTask?.cancel()
        repeatTask = null
        states.clear()
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

    private fun tickHeldInputs() {
        val iterator = states.iterator()
        while (iterator.hasNext()) {
            val (playerId, state) = iterator.next()
            if (state.direction == HandleDirection.NONE || state.direction == HandleDirection.CENTER) continue
            val player = Bukkit.getPlayer(playerId)
            if (player == null || !player.isOnline) {
                iterator.remove()
                continue
            }
            val controller = TrainCommand.controllerFor(player)
            if (controller == null || !TrainCommand.isDrivingControlledTrain(player, controller)) {
                iterator.remove()
                continue
            }

            val heldTicks = state.heldTicks + 1
            if (heldTicks >= INITIAL_REPEAT_DELAY_TICKS &&
                (heldTicks - INITIAL_REPEAT_DELAY_TICKS) % REPEAT_INTERVAL_TICKS == 0
            ) {
                applyDirection(player, state.direction)
            }
            state.heldTicks = heldTicks
        }
    }

    private fun applyDirection(player: Player, direction: HandleDirection) {
        val controller = TrainCommand.controllerFor(player) ?: return
        if (!TrainCommand.isDrivingControlledTrain(player, controller)) return
        val handle = when (direction) {
            HandleDirection.UP -> controller.shiftHandleTowardPower()
            HandleDirection.DOWN -> controller.shiftHandleTowardBrake()
            HandleDirection.CENTER -> {
                controller.coast()
                controller.handle
            }
            HandleDirection.NONE -> return
        }
        player.sendActionBar(Component.text("手柄: $handle"))
    }

    private data class InputState(
        val direction: HandleDirection = HandleDirection.NONE,
        var heldTicks: Int = 0
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
    }
}
