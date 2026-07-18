package work.art1st.mineRails.bukkit.control

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInputEvent
import org.bukkit.event.player.PlayerQuitEvent
import work.art1st.mineRails.bukkit.BukkitPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TrainHandleInputListener(
    private val controlManager: TrainControlManager
) : Listener {
    private val states = ConcurrentHashMap<UUID, InputState>()
    private val repeatTasks = ConcurrentHashMap<UUID, ScheduledTask>()
    private lateinit var plugin: BukkitPlugin

    fun start(plugin: BukkitPlugin) {
        this.plugin = plugin
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun stop() {
        repeatTasks.values.forEach(ScheduledTask::cancel)
        repeatTasks.clear()
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
            states[player.uniqueId] = InputState(direction = direction)
        }

        if (controlManager.controllerFor(player) == null) {
            stopRepeating(player.uniqueId)
        } else {
            ensureRepeating(player)
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        stopRepeating(event.player.uniqueId)
    }

    private fun ensureRepeating(player: Player) {
        if (repeatTasks.containsKey(player.uniqueId)) return
        val task = plugin.platformDispatcher.runForEntityAtFixedRate(
            entity = player,
            initialDelayTicks = 1L,
            periodTicks = 1L,
            action = { scheduledTask -> tickDriver(player, scheduledTask) },
            retired = {
                repeatTasks.remove(player.uniqueId)
                states.remove(player.uniqueId)
            }
        ) ?: return
        val previous = repeatTasks.putIfAbsent(player.uniqueId, task)
        if (previous != null) task.cancel()
    }

    private fun tickDriver(player: Player, task: ScheduledTask) {
        val controller = controlManager.controllerFor(player)
        if (controller == null) {
            task.cancel()
            repeatTasks.remove(player.uniqueId, task)
            states.remove(player.uniqueId)
            return
        }

        val state = states.computeIfAbsent(player.uniqueId) { InputState() }
        state.taskTicks++
        if (state.direction == HandleDirection.UP || state.direction == HandleDirection.DOWN) {
            state.heldTicks++
            if (state.heldTicks >= INITIAL_REPEAT_DELAY_TICKS &&
                (state.heldTicks - INITIAL_REPEAT_DELAY_TICKS) % REPEAT_INTERVAL_TICKS == 0
            ) {
                applyDirection(player, state.direction)
            }
        } else {
            state.heldTicks = 0
        }

        if (state.taskTicks % ACTION_BAR_INTERVAL_TICKS == 0) {
            sendControllerActionBar(player, controller)
        }
    }

    private fun stopRepeating(playerId: UUID) {
        repeatTasks.remove(playerId)?.cancel()
        states.remove(playerId)
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

    private fun sendControllerActionBar(player: Player, controller: RealisticManualController) {
        val speedMps = controller.displaySpeed
        val speedKmh = speedMps * 3.6
        player.sendActionBar(Component.text(
            "方向: ${controller.direction.label}  挡位: ${controller.handle}  速度: ${"%.1f".format(speedKmh)} km/h (${"%.2f".format(speedMps)} m/s)"
        ))
    }

    private data class InputState(
        val direction: HandleDirection = HandleDirection.NONE,
        var heldTicks: Int = 0,
        var taskTicks: Int = 0
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
        private const val ACTION_BAR_INTERVAL_TICKS = 5
    }
}
