package work.art1st.mineRails.bukkit.control

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import org.joml.Vector3d
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.config.model.CarControlConfig
import work.art1st.mineRails.bukkit.config.model.DriverControlEnd
import work.art1st.mineRails.bukkit.extension.train.CarExtension
import work.art1st.mineRails.bukkit.extension.train.renderer
import work.art1st.mineRails.bukkit.render.model.SeatModel
import work.art1st.mineRails.core.api.physics.train.Train
import work.art1st.mineRails.core.physics.train.CoreTrain
import java.util.UUID

class TrainControlManager(
    private val plugin: BukkitPlugin
) : Listener {
    sealed class AcquireResult {
        data class Acquired(val session: ManualControlSession) : AcquireResult()
        data class AlreadyControlled(val ownerId: UUID) : AcquireResult()
        data object MissingKey : AcquireResult()
        data object NotDriverSeat : AcquireResult()
        data object TrainUnavailable : AcquireResult()
    }

    data class ManualControlSession(
        val trainId: String,
        val playerId: UUID,
        val controller: RealisticManualController
    )

    private val sessionsByPlayer: MutableMap<UUID, ManualControlSession> = mutableMapOf()
    private val sessionsByTrain: MutableMap<String, ManualControlSession> = mutableMapOf()
    private val dialog = TrainControlDialog(plugin, this)
    private var bindingScanTask: BukkitTask? = null

    fun start() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        SeatModel.SeatInteractionListener.addDriverSeatVacatedHandler { player, train ->
            val session = sessionsByPlayer[player.uniqueId] ?: return@addDriverSeatVacatedHandler
            if (session.trainId == train.id) releaseManualControl(player.uniqueId)
        }
        bindingScanTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable { scanBoundKeys() }, 20L, 20L)
    }

    fun stop() {
        bindingScanTask?.cancel()
        bindingScanTask = null
        clear()
    }

    fun ownerOf(train: Train): UUID? = sessionsByTrain[train.id]?.playerId

    fun sessionFor(player: Player): ManualControlSession? = sessionsByPlayer[player.uniqueId]

    fun sessionFor(train: Train): ManualControlSession? = sessionsByTrain[train.id]

    fun controllerFor(player: Player): RealisticManualController? {
        val session = sessionFor(player) ?: return null
        val train = session.controller.controlledTrain
        return if (SeatModel.SeatInteractionListener.isPlayerDrivingTrain(player, train)) session.controller else null
    }

    fun acquireManualControl(player: Player, train: Train): AcquireResult {
        val coreTrain = train as? CoreTrain ?: return AcquireResult.TrainUnavailable
        if (!playerHasBoundKeyFor(player, train)) return AcquireResult.MissingKey
        val driverSeat = SeatModel.SeatInteractionListener.driverSeatFor(player)
        if (driverSeat?.train !== train) return AcquireResult.NotDriverSeat
        sessionsByTrain[train.id]?.let { existing ->
            if (existing.playerId != player.uniqueId) return AcquireResult.AlreadyControlled(existing.playerId)
            return AcquireResult.Acquired(existing)
        }

        releaseManualControl(player.uniqueId)
        alignTrainHeadToDriverSeat(coreTrain, driverSeat.controlEnd)
        val controller = coreTrain.controller as? RealisticManualController ?: run {
            val (controlConfig, carControls) = resolveControlProfile(coreTrain)
            RealisticManualController(coreTrain, coreTrain.world, controlConfig, carControls).also { coreTrain.setController(it) }
        }
        controller.markCurrentPhysicalDirectionAsForward()
        controller.setDirection(TrainDriveDirection.NEUTRAL)
        controller.coast()
        return AcquireResult.Acquired(registerManualControl(player, coreTrain, controller))
    }

    fun registerManualControl(player: Player, train: Train, controller: RealisticManualController): ManualControlSession {
        releaseManualControl(player.uniqueId)
        val previousForTrain = sessionsByTrain.remove(train.id)
        if (previousForTrain != null) {
            sessionsByPlayer.remove(previousForTrain.playerId)
        }
        val session = ManualControlSession(
            trainId = train.id,
            playerId = player.uniqueId,
            controller = controller
        )
        sessionsByPlayer[player.uniqueId] = session
        sessionsByTrain[train.id] = session
        return session
    }

    fun releaseManualControl(player: Player): ManualControlSession? = releaseManualControl(player.uniqueId)

    fun releaseManualControl(playerId: UUID): ManualControlSession? {
        val session = sessionsByPlayer.remove(playerId) ?: return null
        sessionsByTrain.remove(session.trainId)
        session.controller.coast()
        return session
    }

    fun releaseTrain(train: Train): ManualControlSession? {
        val session = sessionsByTrain.remove(train.id) ?: return null
        sessionsByPlayer.remove(session.playerId)
        stopController(session.controller)
        return session
    }

    fun clear() {
        sessionsByPlayer.values.forEach { session -> stopController(session.controller) }
        plugin.worldManager.railWorlds.values.forEach { railWorld ->
            railWorld.allTrains.forEach { train ->
                ((train as? CoreTrain)?.controller as? RealisticManualController)?.let(::stopController)
            }
        }
        sessionsByPlayer.clear()
        sessionsByTrain.clear()
    }

    private fun stopController(controller: RealisticManualController) {
        controller.coast()
        (controller.controlledTrain as? CoreTrain)?.let { controlledTrain ->
            if (controlledTrain.controller === controller) controlledTrain.setController(null)
        }
    }

    private fun scanBoundKeys() {
        plugin.server.onlinePlayers.forEach { player ->
            validateHeldKeyBinding(player, player.inventory.itemInMainHand)
            validateHeldKeyBinding(player, player.inventory.itemInOffHand)
        }
    }

    private fun validateHeldKeyBinding(player: Player, item: ItemStack) {
        if (!TrainKeyItem.isKey(item)) return
        if (TrainKeyItem.boundTrainId(item) == null && TrainKeyItem.boundWorldId(item) == null) return
        val train = resolveBoundTrain(item)
        if (train != null && isWithinBindingDistance(player, train)) return
        TrainKeyItem.unbind(item)
        player.sendActionBar(Component.text("列车钥匙已自动解绑", NamedTextColor.YELLOW))
    }

    @EventHandler
    fun onPlayerUseTrainKey(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val item = event.item ?: return
        if (!TrainKeyItem.isKey(item)) return

        event.isCancelled = true
        val player = event.player
        val boundTrain = resolveBoundTrain(item)
        if (boundTrain != null) {
            if (isWithinBindingDistance(player, boundTrain)) {
                dialog.show(player, boundTrain)
            } else {
                TrainKeyItem.unbind(item)
                player.sendActionBar(Component.text("距离列车过远，钥匙已解绑", NamedTextColor.YELLOW))
            }
            return
        }

        if (TrainKeyItem.boundTrainId(item) != null || TrainKeyItem.boundWorldId(item) != null) {
            TrainKeyItem.unbind(item)
            player.sendActionBar(Component.text("绑定的列车不可用，钥匙已解绑", NamedTextColor.YELLOW))
            return
        }

        val train = SeatModel.SeatInteractionListener.drivingTrainFor(player)
        if (train == null) {
            player.sendActionBar(Component.text("请先坐在驾驶座上再绑定列车", NamedTextColor.RED))
            return
        }
        TrainKeyItem.bind(item, train)
        player.sendActionBar(Component.text("钥匙已绑定列车: ${train.id}", NamedTextColor.GREEN))
        dialog.show(player, train)
    }

    fun playerHasBoundKeyFor(player: Player, train: Train): Boolean {
        return player.inventory.contents.asSequence()
            .filterNotNull()
            .any { item -> item.isBoundTo(player, train) }
    }

    fun unbindKeyFor(player: Player, train: Train): Boolean {
        val handItems = listOf(player.inventory.itemInMainHand, player.inventory.itemInOffHand)
        val item = handItems.firstOrNull { it.isBoundTo(player, train) } ?: return false
        return TrainKeyItem.unbind(item)
    }

    private fun ItemStack.isBoundTo(player: Player, train: Train): Boolean {
        return TrainKeyItem.isKey(this) &&
            TrainKeyItem.boundTrainId(this) == train.id &&
            TrainKeyItem.boundWorldId(this) == player.world.uid
    }

    private fun alignTrainHeadToDriverSeat(train: CoreTrain, controlEnd: DriverControlEnd?) {
        val targetEnd = controlEnd ?: return
        val activeHeadEnd = if (train.isReversed) DriverControlEnd.TAIL else DriverControlEnd.HEAD
        if (targetEnd != activeHeadEnd) train.reverse()
    }

    private fun resolveBoundTrain(item: ItemStack): Train? {
        val trainId = TrainKeyItem.boundTrainId(item) ?: return null
        val worldId = TrainKeyItem.boundWorldId(item) ?: return null
        val bukkitWorld = plugin.server.getWorld(worldId) ?: return null
        val railWorld = plugin.worldManager.railWorlds[bukkitWorld] ?: return null
        return railWorld.getTrain(trainId)
    }

    private fun isWithinBindingDistance(player: Player, train: Train): Boolean {
        val maxDistance = plugin.mineRailsConfig.trainKeyBindDistance
        if (maxDistance <= 0.0) return false
        val playerPos = Vector3d(player.location.x, player.location.y, player.location.z)
        return train.cars.any { car -> car.position.distance(playerPos) <= maxDistance }
    }

    private fun resolveControlProfile(train: CoreTrain): Pair<work.art1st.mineRails.bukkit.config.model.TrainControlConfig, List<CarControlConfig>> {
        val carModelNames = train.cars.map { car ->
            runCatching { (car.renderer as? CarExtension)?.carModelName }.getOrNull()
        }
        val packageName = carModelNames.firstNotNullOfOrNull { modelName ->
            modelName?.let { plugin.modelManager.getCar(it)?.packageName }
        }
        val trainControl = plugin.modelManager.getTrainControl(packageName)
        val carControls = carModelNames.map { modelName ->
            modelName?.let { plugin.modelManager.getCar(it)?.control } ?: CarControlConfig()
        }
        return trainControl to carControls
    }
}
