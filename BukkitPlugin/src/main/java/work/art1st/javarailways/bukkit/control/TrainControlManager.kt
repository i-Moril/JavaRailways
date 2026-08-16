package work.art1st.javarailways.bukkit.control

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.joml.Vector3d
import work.art1st.javarailways.basic.pathFinding.basicWorldData
import work.art1st.javarailways.basic.timetable.TimetableControlMode
import work.art1st.javarailways.basic.timetable.TimetableControlTransferResult
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.config.model.CarControlConfig
import work.art1st.javarailways.bukkit.config.model.DriverControlEnd
import work.art1st.javarailways.bukkit.extension.train.carRendererOrNull
import work.art1st.javarailways.bukkit.extension.train.rendererOrNull
import work.art1st.javarailways.bukkit.render.model.SeatModel
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.physics.train.CoreTrain
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TrainControlManager(
    private val plugin: BukkitPlugin
) : Listener {
    sealed class AcquireResult {
        data class Acquired(val session: ManualControlSession) : AcquireResult()
        data class AlreadyControlled(val ownerId: UUID) : AcquireResult()
        data class ConfigError(val message: String) : AcquireResult()
        data object MissingKey : AcquireResult()
        data object NotDriverSeat : AcquireResult()
        data object TrainUnavailable : AcquireResult()
    }

    data class ManualControlSession(
        val trainId: String,
        val playerId: UUID,
        val controller: RealisticManualController,
        val controlEnd: DriverControlEnd?,
        var timetableMode: TimetableControlMode
    )

    private val sessionsByPlayer: MutableMap<UUID, ManualControlSession> = ConcurrentHashMap()
    private val sessionsByTrain: MutableMap<String, ManualControlSession> = ConcurrentHashMap()
    private val dialog = TrainControlDialog(plugin, this)
    private val bindingScanTasks = ConcurrentHashMap<UUID, ScheduledTask>()

    fun start() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        SeatModel.SeatInteractionListener.addDriverSeatVacatedHandler { player, train ->
            val session = sessionsByPlayer[player.uniqueId] ?: return@addDriverSeatVacatedHandler
            if (session.trainId == train.id) releaseManualControl(player.uniqueId)
        }
        plugin.server.onlinePlayers.forEach(::ensureBindingScan)
    }

    fun stop() {
        bindingScanTasks.values.forEach(ScheduledTask::cancel)
        bindingScanTasks.clear()
        clear()
    }

    fun ownerOf(train: Train): UUID? = sessionsByTrain[train.id]?.playerId

    fun sessionFor(player: Player): ManualControlSession? = sessionsByPlayer[player.uniqueId]

    fun sessionFor(train: Train): ManualControlSession? = sessionsByTrain[train.id]

    fun operatingControlEndFor(train: Train): DriverControlEnd {
        sessionsByTrain[train.id]?.controlEnd?.let { return it }
        return if ((train as? CoreTrain)?.isReversed == true) DriverControlEnd.TAIL else DriverControlEnd.HEAD
    }

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
        val existingManualController = coreTrain.controller as? RealisticManualController
        val resolvedControl = if (existingManualController == null) {
            try {
                resolveControlProfile(coreTrain)
            } catch (e: IllegalArgumentException) {
                return AcquireResult.ConfigError(e.message ?: "列车控制配置无效")
            }
        } else {
            null
        }
        val transferOwner = player.uniqueId.toString()
        val transferSpeedMetersPerSecond = coreTrain.speed
        val timetableMode = when (val transfer =
            coreTrain.world.basicWorldData.dispatcher.enterManualRoutedControl(
                coreTrain.world,
                coreTrain.id,
                transferOwner
            )
        ) {
            is TimetableControlTransferResult.Failure ->
                return AcquireResult.ConfigError(transfer.reason)
            is TimetableControlTransferResult.ManualRouted -> TimetableControlMode.MANUAL_ROUTED
            TimetableControlTransferResult.NotAssigned -> TimetableControlMode.FULL_MANUAL
            is TimetableControlTransferResult.Suspended -> TimetableControlMode.FULL_MANUAL
        }
        alignTrainHeadToDriverSeat(coreTrain, driverSeat.controlEnd)
        val controller = existingManualController ?: resolvedControl!!.let { (controlConfig, carControls) ->
            RealisticManualController(
                coreTrain,
                coreTrain.world,
                controlConfig,
                carControls,
                initialSpeedMetersPerSecond = transferSpeedMetersPerSecond
            ).also { coreTrain.setController(it) }
        }
        controller.markCurrentPhysicalDirectionAsForward()
        controller.setDirection(TrainDriveDirection.NEUTRAL)
        controller.coast()
        if (timetableMode == TimetableControlMode.MANUAL_ROUTED &&
            !coreTrain.world.basicWorldData.dispatcher.bindManualRoutedController(
                coreTrain.id,
                transferOwner,
                controller
            )
        ) {
            coreTrain.world.basicWorldData.dispatcher.switchToFullManual(coreTrain.id, transferOwner)
            controller.emergencyBrake()
            if (coreTrain.controller === controller) coreTrain.setController(null)
            return AcquireResult.ConfigError("MANUAL_ROUTED controller 身份绑定失败")
        }
        return AcquireResult.Acquired(
            registerManualControl(
                player,
                coreTrain,
                controller,
                driverSeat.controlEnd,
                timetableMode
            )
        )
    }

    fun registerManualControl(
        player: Player,
        train: Train,
        controller: RealisticManualController,
        controlEnd: DriverControlEnd?,
        timetableMode: TimetableControlMode = TimetableControlMode.FULL_MANUAL
    ): ManualControlSession {
        releaseManualControl(player.uniqueId)
        val previousForTrain = sessionsByTrain.remove(train.id)
        if (previousForTrain != null) {
            sessionsByPlayer.remove(previousForTrain.playerId)
        }
        val session = ManualControlSession(
            trainId = train.id,
            playerId = player.uniqueId,
            controller = controller,
            controlEnd = controlEnd,
            timetableMode = timetableMode
        )
        sessionsByPlayer[player.uniqueId] = session
        sessionsByTrain[train.id] = session
        return session
    }

    fun switchToFullManual(player: Player): TimetableControlTransferResult {
        val session = sessionsByPlayer[player.uniqueId]
            ?: return TimetableControlTransferResult.NotAssigned
        val train = session.controller.controlledTrain
        val result = train.world.basicWorldData.dispatcher.switchToFullManual(
            train.id,
            player.uniqueId.toString()
        )
        if (result is TimetableControlTransferResult.Suspended) {
            session.timetableMode = TimetableControlMode.FULL_MANUAL
        }
        return result
    }

    fun releaseManualControl(player: Player): ManualControlSession? = releaseManualControl(player.uniqueId)

    fun releaseManualControl(playerId: UUID): ManualControlSession? {
        val session = sessionsByPlayer.remove(playerId) ?: return null
        sessionsByTrain.remove(session.trainId)
        if (notifyControlTransferReleased(session)) {
            session.controller.emergencyBrake()
        } else {
            session.controller.coast()
        }
        return session
    }

    fun releaseTrain(train: Train): ManualControlSession? {
        val session = sessionsByTrain.remove(train.id) ?: return null
        sessionsByPlayer.remove(session.playerId)
        notifyControlTransferReleased(session)
        stopController(session.controller)
        return session
    }

    fun clear() {
        sessionsByPlayer.values.forEach { session ->
            notifyControlTransferReleased(session)
            stopController(session.controller)
        }
        plugin.worldManager.railWorlds.values.forEach { railWorld ->
            railWorld.allTrains.forEach { train ->
                ((train as? CoreTrain)?.controller as? RealisticManualController)?.let(::stopController)
            }
        }
        sessionsByPlayer.clear()
        sessionsByTrain.clear()
    }

    private fun notifyControlTransferReleased(session: ManualControlSession): Boolean {
        val train = session.controller.controlledTrain
        return train.world.basicWorldData.dispatcher.releaseControlTransfer(
            train.id,
            session.playerId.toString()
        )
    }

    private fun stopController(controller: RealisticManualController) {
        controller.coast()
        (controller.controlledTrain as? CoreTrain)?.let { controlledTrain ->
            if (controlledTrain.controller === controller) controlledTrain.setController(null)
        }
    }

    private fun ensureBindingScan(player: Player) {
        if (bindingScanTasks.containsKey(player.uniqueId)) return
        val task = plugin.platformDispatcher.runForEntityAtFixedRate(
            entity = player,
            initialDelayTicks = 20L,
            periodTicks = 20L,
            action = { scanBoundKeys(player) },
            retired = { bindingScanTasks.remove(player.uniqueId) }
        ) ?: return
        val previous = bindingScanTasks.putIfAbsent(player.uniqueId, task)
        if (previous != null) task.cancel()
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        ensureBindingScan(event.player)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        bindingScanTasks.remove(event.player.uniqueId)?.cancel()
        releaseManualControl(event.player.uniqueId)
    }

    private fun scanBoundKeys(player: Player) {
        var unboundAny = false
        val inventory = player.inventory

        val storageContents = inventory.storageContents
        storageContents.forEachIndexed { index, item ->
            if (item != null && validateKeyBinding(player, item, "inventory[$index]")) {
                storageContents[index] = item
                unboundAny = true
            }
        }
        inventory.storageContents = storageContents

        val offHand = inventory.itemInOffHand
        if (validateKeyBinding(player, offHand, "offhand")) {
            inventory.setItemInOffHand(offHand)
            unboundAny = true
        }

        if (unboundAny) {
            player.sendActionBar(Component.text("列车钥匙已自动解绑", NamedTextColor.YELLOW))
        }
    }

    private fun validateKeyBinding(player: Player, item: ItemStack, source: String): Boolean {
        if (!TrainKeyItem.isKey(item)) return false
        val trainId = TrainKeyItem.boundTrainId(item)
        val worldId = TrainKeyItem.boundWorldId(item)
        if (trainId == null && worldId == null) return false
        val train = resolveBoundTrain(item)
        if (train == null) {
            logKeyUnbind(player, source, trainId, worldId, "train-not-found")
            return TrainKeyItem.unbind(item)
        }
        val maxDistance = plugin.javaRailwaysConfig.trainKeyBindDistance
        val minDistance = minDistanceToTrainCenters(player, train)
        if (maxDistance > 0.0 && minDistance != null && minDistance <= maxDistance) return false
        val distanceText = minDistance?.let { "%.2f".format(it) } ?: "none"
        logKeyUnbind(player, source, trainId, worldId, "too-far minDistance=$distanceText maxDistance=${"%.2f".format(maxDistance)}")
        return TrainKeyItem.unbind(item)
    }

    @EventHandler
    fun onPlayerUseTrainKey(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val player = event.player
        val hand = event.hand
        val item = keyItemInUse(player, hand) ?: event.item ?: return
        if (!TrainKeyItem.isKey(item)) return

        event.isCancelled = true
        val boundTrain = resolveBoundTrain(item)
        if (boundTrain != null) {
            if (isWithinBindingDistance(player, boundTrain)) {
                dialog.show(player, boundTrain)
            } else {
                logKeyUnbind(player, handName(hand), TrainKeyItem.boundTrainId(item), TrainKeyItem.boundWorldId(item), "right-click-too-far")
                TrainKeyItem.unbind(item)
                setKeyItemInUse(player, hand, item)
                player.sendActionBar(Component.text("距离列车过远，钥匙已解绑", NamedTextColor.YELLOW))
            }
            return
        }

        if (TrainKeyItem.boundTrainId(item) != null || TrainKeyItem.boundWorldId(item) != null) {
            logKeyUnbind(player, handName(hand), TrainKeyItem.boundTrainId(item), TrainKeyItem.boundWorldId(item), "right-click-train-not-found")
            TrainKeyItem.unbind(item)
            setKeyItemInUse(player, hand, item)
            player.sendActionBar(Component.text("绑定的列车不可用，钥匙已解绑", NamedTextColor.YELLOW))
            return
        }

        val train = SeatModel.SeatInteractionListener.drivingTrainFor(player)
        if (train == null) {
            player.sendActionBar(Component.text("未绑定列车", NamedTextColor.YELLOW))
            return
        }
        TrainKeyItem.bind(item, train)
        setKeyItemInUse(player, hand, item)
        plugin.logger.info("[TrainKey] Bound key player=${player.name} source=${handName(hand)} train=${train.id}")
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

    private fun resolveBoundTrain(item: ItemStack): Train? {
        val trainId = TrainKeyItem.boundTrainId(item) ?: return null
        val worldId = TrainKeyItem.boundWorldId(item) ?: return null
        val bukkitWorld = plugin.server.getWorld(worldId) ?: return null
        val railWorld = plugin.worldManager.railWorlds[bukkitWorld] ?: return null
        return railWorld.getTrain(trainId)
    }

    private fun isWithinBindingDistance(player: Player, train: Train): Boolean {
        val maxDistance = plugin.javaRailwaysConfig.trainKeyBindDistance
        if (maxDistance <= 0.0) return false
        return minDistanceToTrainCenters(player, train)?.let { it <= maxDistance } ?: false
    }

    private fun minDistanceToTrainCenters(player: Player, train: Train): Double? {
        val playerPos = Vector3d(player.location.x, player.location.y, player.location.z)
        return train.cars.minOfOrNull { car -> car.position.distance(playerPos) }
    }

    private fun keyItemInUse(player: Player, hand: EquipmentSlot?): ItemStack? {
        return when (hand) {
            EquipmentSlot.HAND -> player.inventory.itemInMainHand
            EquipmentSlot.OFF_HAND -> player.inventory.itemInOffHand
            else -> null
        }
    }

    private fun setKeyItemInUse(player: Player, hand: EquipmentSlot?, item: ItemStack) {
        when (hand) {
            EquipmentSlot.HAND -> player.inventory.setItemInMainHand(item)
            EquipmentSlot.OFF_HAND -> player.inventory.setItemInOffHand(item)
            else -> Unit
        }
    }

    private fun handName(hand: EquipmentSlot?): String {
        return when (hand) {
            EquipmentSlot.HAND -> "mainhand"
            EquipmentSlot.OFF_HAND -> "offhand"
            null -> "unknown-hand"
            else -> hand.name.lowercase()
        }
    }

    private fun logKeyUnbind(player: Player, source: String, trainId: String?, worldId: UUID?, reason: String) {
        plugin.logger.info(
            "[TrainKey] Auto-unbind player=${player.name} source=$source train=${trainId ?: "<none>"} " +
                "world=${worldId ?: "<none>"} reason=$reason"
        )
    }

    private fun resolveControlProfile(train: CoreTrain): Pair<work.art1st.javarailways.bukkit.config.model.TrainControlConfig, List<CarControlConfig>> {
        val carModelNames = train.cars.mapIndexed { index, car ->
            car.carRendererOrNull?.carModelName
                ?: throw IllegalArgumentException("列车 ${train.id} 的第 ${index + 1} 节车厢缺少 carModelName，无法解析控制配置")
        }
        val renderer = train.rendererOrNull
            ?: throw IllegalArgumentException("列车 ${train.id} 没有渲染扩展，无法解析控制配置")
        val trainControl = renderer.resolveControl()
        val carControls = carModelNames.mapIndexed { index, modelName ->
            renderer.resolveCarControl(modelName)
                ?: throw IllegalArgumentException("列车 ${train.id} 的第 ${index + 1} 节车厢模型 '$modelName' 没有控制配置")
        }
        return trainControl to carControls
    }

}

/**
 * Make the cab selected by the driver the physical head before installing the new manual controller.
 * Reversing through the outgoing controller is incorrect because it is detached immediately after
 * this call, so its queued reverse request would never reach a physics tick.
 */
internal fun alignTrainHeadToDriverSeat(train: CoreTrain, controlEnd: DriverControlEnd?) {
    val targetEnd = controlEnd ?: return
    val activeHeadEnd = if (train.isReversed) DriverControlEnd.TAIL else DriverControlEnd.HEAD
    if (targetEnd != activeHeadEnd) train.reverse()
}
