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
import work.art1st.javarailways.basic.control.RealisticManualController
import work.art1st.javarailways.basic.control.TrainDriveDirection
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.timetable.TimetableControlMode
import work.art1st.javarailways.simpleato.timetable.TimetableControlTransferResult
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
import java.util.concurrent.CompletableFuture

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
        @Volatile var timetableMode: TimetableControlMode
    )

    private val sessionsByPlayer: MutableMap<UUID, ManualControlSession> = ConcurrentHashMap()
    private val sessionsByTrain: MutableMap<Train, ManualControlSession> = ConcurrentHashMap()
    private val dialog = TrainControlDialog(plugin, this)
    private val bindingScanTasks = ConcurrentHashMap<UUID, ScheduledTask>()
    private class AcquisitionRequest(val train: CoreTrain)
    private val pendingAcquisitions = ConcurrentHashMap<UUID, AcquisitionRequest>()

    fun start() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        SeatModel.SeatInteractionListener.addDriverSeatVacatedHandler { player, train ->
            pendingAcquisitions.remove(player.uniqueId)
            val session = sessionsByPlayer[player.uniqueId] ?: return@addDriverSeatVacatedHandler
            if (session.controller.controlledTrain === train) releaseManualControl(player.uniqueId)
        }
        plugin.server.onlinePlayers.forEach(::ensureBindingScan)
    }

    fun stop() {
        bindingScanTasks.values.forEach(ScheduledTask::cancel)
        bindingScanTasks.clear()
        pendingAcquisitions.clear()
        sessionsByPlayer.clear()
        sessionsByTrain.clear()
    }

    fun ownerOf(train: Train): UUID? = sessionsByTrain[train]?.playerId

    fun sessionFor(player: Player): ManualControlSession? = sessionsByPlayer[player.uniqueId]

    fun sessionFor(train: Train): ManualControlSession? = sessionsByTrain[train]

    fun operatingControlEndFor(train: Train): DriverControlEnd {
        sessionsByTrain[train]?.controlEnd?.let { return it }
        return if ((train as? CoreTrain)?.isReversed == true) DriverControlEnd.TAIL else DriverControlEnd.HEAD
    }

    fun controllerFor(player: Player): RealisticManualController? {
        val session = sessionFor(player) ?: return null
        val train = session.controller.controlledTrain
        return if (SeatModel.SeatInteractionListener.isPlayerDrivingTrain(player, train)) session.controller else null
    }

    fun acquireManualControl(player: Player, train: Train): CompletableFuture<AcquireResult> {
        val coreTrain = train as? CoreTrain
            ?: return CompletableFuture.completedFuture(AcquireResult.TrainUnavailable)
        if (!playerHasBoundKeyFor(player, train)) return CompletableFuture.completedFuture(AcquireResult.MissingKey)
        val driverSeat = SeatModel.SeatInteractionListener.driverSeatFor(player)
        if (driverSeat?.train !== train) return CompletableFuture.completedFuture(AcquireResult.NotDriverSeat)
        val playerId = player.uniqueId
        val controlEnd = driverSeat.controlEnd
        val expectedController = coreTrain.controller
        val previous = sessionsByPlayer[playerId]
        val release = if (previous != null && previous.controller.controlledTrain.world !== coreTrain.world) {
            releaseManualControl(playerId)
        } else CompletableFuture.completedFuture(null)
        val request = AcquisitionRequest(coreTrain)
        pendingAcquisitions[playerId] = request
        return release.thenCompose {
            coreTrain.world.physicsCoordinator.submitBetweenFrames {
                if (!pendingAcquisitions.remove(playerId, request) ||
                    coreTrain.world.getTrain(coreTrain.id) !== coreTrain ||
                    coreTrain.controller !== expectedController ||
                    sessionsByPlayer[playerId] !== previous?.takeIf {
                        it.controller.controlledTrain.world === coreTrain.world
                    }
                ) return@submitBetweenFrames AcquireResult.TrainUnavailable
                acquireManualControlOnOwner(playerId, coreTrain, controlEnd)
            }
        }.whenComplete { _, _ -> pendingAcquisitions.remove(playerId, request) }
    }

    private fun acquireManualControlOnOwner(
        playerId: UUID, coreTrain: CoreTrain, controlEnd: DriverControlEnd?
    ): AcquireResult {
        coreTrain.world.physicsCoordinator.ensureBetweenFrames("TrainControlManager.acquireManualControl")
        val train: Train = coreTrain
        sessionsByTrain[train]?.let { existing ->
            if (coreTrain.controller !== existing.controller) {
                retireSession(existing)
            } else {
                if (existing.playerId != playerId) return AcquireResult.AlreadyControlled(existing.playerId)
                return AcquireResult.Acquired(existing)
            }
        }

        releaseManualControlOnOwner(playerId)
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
        val transferOwner = playerId.toString()
        val transferSpeedMetersPerSecond = coreTrain.speed
        val timetableMode = when (val transfer =
            coreTrain.world.simpleAtoWorldData.dispatcher.enterManualRoutedControl(
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
        alignTrainHeadToDriverSeat(coreTrain, controlEnd)
        val controller = existingManualController ?: resolvedControl!!.let { (controlConfig, carControls) ->
            RealisticManualController(
                coreTrain,
                coreTrain.world,
                controlConfig,
                carControls,
                plugin.physicsConfig.track.gravityMetersPerSecondSquared,
                initialSpeedMetersPerSecond = transferSpeedMetersPerSecond,
                profileResolver = { car -> car.carRendererOrNull?.resolveCarControl() }
            ).also { coreTrain.setController(it) }
        }
        controller.markCurrentPhysicalDirectionAsForward()
        controller.applyDirection(TrainDriveDirection.NEUTRAL)
        controller.coast()
        if (timetableMode == TimetableControlMode.MANUAL_ROUTED &&
            !coreTrain.world.simpleAtoWorldData.dispatcher.bindManualRoutedController(
                coreTrain.id,
                transferOwner,
                controller
            )
        ) {
            coreTrain.world.simpleAtoWorldData.dispatcher.switchToFullManual(coreTrain.id, transferOwner)
            controller.emergencyBrake()
            if (coreTrain.controller === controller) coreTrain.setController(null)
            return AcquireResult.ConfigError("MANUAL_ROUTED controller 身份绑定失败")
        }
        return AcquireResult.Acquired(
            registerManualControl(
                playerId,
                coreTrain,
                controller,
                controlEnd,
                timetableMode
            )
        )
    }

    fun registerManualControl(
        playerId: UUID,
        train: Train,
        controller: RealisticManualController,
        controlEnd: DriverControlEnd?,
        timetableMode: TimetableControlMode = TimetableControlMode.FULL_MANUAL
    ): ManualControlSession {
        val coreTrain = train as CoreTrain
        coreTrain.world.physicsCoordinator.ensureBetweenFrames("TrainControlManager.registerManualControl")
        check(coreTrain.world.getTrain(coreTrain.id) === coreTrain && coreTrain.controller === controller)
        releaseManualControlOnOwner(playerId)
        val previousForTrain = sessionsByTrain.remove(train)
        if (previousForTrain != null) {
            sessionsByPlayer.remove(previousForTrain.playerId)
        }
        val session = ManualControlSession(
            trainId = train.id,
            playerId = playerId,
            controller = controller,
            controlEnd = controlEnd,
            timetableMode = timetableMode
        )
        sessionsByPlayer[playerId] = session
        sessionsByTrain[train] = session
        return session
    }

    fun switchToFullManual(player: Player): CompletableFuture<TimetableControlTransferResult> {
        val playerId = player.uniqueId
        val session = sessionsByPlayer[playerId]
            ?: return CompletableFuture.completedFuture(TimetableControlTransferResult.NotAssigned)
        val train = session.controller.controlledTrain as CoreTrain
        return train.world.physicsCoordinator.submitBetweenFrames {
            check(sessionsByPlayer[playerId] === session && train.world.getTrain(train.id) === train &&
                train.controller === session.controller) {
                "Manual control session changed before transfer"
            }
            switchToFullManualOnOwner(session)
        }
    }

    private fun switchToFullManualOnOwner(session: ManualControlSession): TimetableControlTransferResult {
        val train = session.controller.controlledTrain
        val result = train.world.simpleAtoWorldData.dispatcher.switchToFullManual(
            train.id,
            session.playerId.toString()
        )
        if (result is TimetableControlTransferResult.Suspended) {
            session.timetableMode = TimetableControlMode.FULL_MANUAL
        }
        return result
    }

    fun releaseManualControl(player: Player): CompletableFuture<ManualControlSession?> = releaseManualControl(player.uniqueId)

    fun releaseManualControl(playerId: UUID): CompletableFuture<ManualControlSession?> {
        pendingAcquisitions.remove(playerId)
        val session = sessionsByPlayer[playerId] ?: return CompletableFuture.completedFuture(null)
        val train = session.controller.controlledTrain as CoreTrain
        return train.world.physicsCoordinator.submitBetweenFrames {
            if (sessionsByPlayer[playerId] !== session) return@submitBetweenFrames null
            if (train.world.getTrain(train.id) !== train || train.controller !== session.controller) {
                retireSession(session)
                return@submitBetweenFrames null
            }
            releaseManualControlOnOwner(playerId)
        }
    }

    private fun releaseManualControlOnOwner(playerId: UUID): ManualControlSession? {
        val session = sessionsByPlayer[playerId] ?: return null
        (session.controller.controlledTrain as CoreTrain).world.physicsCoordinator.ensureBetweenFrames(
            "TrainControlManager.releaseManualControl"
        )
        val train = session.controller.controlledTrain as CoreTrain
        retireSession(session)
        if (train.world.getTrain(train.id) !== train || train.controller !== session.controller) return session
        if (notifyControlTransferReleased(session)) {
            session.controller.emergencyBrake()
        } else {
            session.controller.coast()
        }
        return session
    }

    fun releaseTrain(train: Train): ManualControlSession? {
        (train as CoreTrain).world.physicsCoordinator.ensureBetweenFrames("TrainControlManager.releaseTrain")
        val session = sessionsByTrain[train] ?: return null
        retireSession(session)
        if (train.controller != null && train.controller !== session.controller) return session
        notifyControlTransferReleased(session)
        stopController(session.controller)
        return session
    }

    fun releaseWorldOnOwner(world: work.art1st.javarailways.core.physics.CoreWorld) {
        world.physicsCoordinator.ensureBetweenFrames("TrainControlManager.releaseWorldOnOwner")
        sessionsByPlayer.values.filter { it.controller.controlledTrain.world === world }.forEach { session ->
            releaseTrain(session.controller.controlledTrain)
        }
        world.allTrains.forEach { train ->
            (train.controller as? RealisticManualController)?.let(::stopController)
        }
    }

    /** Retire adapter-owned references even when a failed/closed world cannot accept owner work. */
    fun retireWorld(world: work.art1st.javarailways.core.physics.CoreWorld) {
        pendingAcquisitions.entries.removeIf { it.value.train.world === world }
        sessionsByPlayer.values.forEach { session ->
            val train = session.controller.controlledTrain
            if (train.world === world) retireSession(session)
        }
    }

    private fun retireSession(session: ManualControlSession) {
        sessionsByPlayer.remove(session.playerId, session)
        sessionsByTrain.remove(session.controller.controlledTrain, session)
    }

    private fun notifyControlTransferReleased(session: ManualControlSession): Boolean {
        val train = session.controller.controlledTrain
        return train.world.simpleAtoWorldData.dispatcher.releaseControlTransfer(
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
        val renderer = train.rendererOrNull
            ?: throw IllegalArgumentException("列车 ${train.id} 没有渲染扩展，无法解析控制配置")
        val trainControl = renderer.resolveControl()
        val carControls = train.cars.mapIndexed { index, car ->
            val carRenderer = car.carRendererOrNull
            val modelName = carRenderer?.carModelName
                ?: throw IllegalArgumentException("列车 ${train.id} 的第 ${index + 1} 节车厢缺少 carModelName，无法解析控制配置")
            carRenderer.resolveCarControl()
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
