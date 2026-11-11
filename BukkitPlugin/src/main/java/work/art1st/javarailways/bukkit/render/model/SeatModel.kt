package work.art1st.javarailways.bukkit.render.model

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.HandlerList
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.joml.Vector3dc
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.config.model.DriverControlEnd
import work.art1st.javarailways.bukkit.extension.bukkitWorldData
import work.art1st.javarailways.bukkit.render.Animation
import work.art1st.javarailways.bukkit.util.BukkitThreadOwnership
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.train.Car
import work.art1st.javarailways.core.api.physics.train.Train
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import work.art1st.javarailways.core.api.util.DestructionSteps
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class SeatModel internal constructor(
    world: World,
    state: ModelState,
    attachedModels: Collection<AbstractModel>,
    animation: Map<String, Animation>,
    private val initialLocation: Location,
    private val ownerCar: Car?,
    private val driverSeat: Boolean,
    private val controlEnd: DriverControlEnd?
) : EntityModel(world, state, attachedModels, animation, null) {
    @Volatile
    private var carrierEntity: Entity? = null
    @Volatile
    private var seatBinding: SeatBinding? = null
    @Volatile
    private var latestCarrierLocation: Location? = null
    private val activePlayers = ConcurrentHashMap<UUID, Player>()
    private val movementRevision = AtomicLong(0L)
    private val spawnScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile
    private var retired = false
    private val pendingRemovals = mutableListOf<CompletableFuture<Void>>()

    init {
        SeatInteractionListener.ensureRegistered()
    }

    /** Seat hitboxes are created only while at least one audience player is close enough to use them. */
    override fun spawnEntity() {
        if (entity != null || retired || !spawnScheduled.compareAndSet(false, true)) return
        val bukkitWorld = world.bukkitWorldData.bukkitWorld
        val position = state.worldTransform.position
        val spawnLocation = Location(
            bukkitWorld,
            position.x(),
            position.y(),
            position.z(),
            initialLocation.yaw,
            initialLocation.pitch
        )
        BukkitPlugin.INSTANCE.platformDispatcher.runAt(spawnLocation) {
            try {
                synchronized(this@SeatModel) {
                    if (retired || activePlayers.isEmpty() || entity != null) return@synchronized
                    val hitbox = bukkitWorld.spawn(spawnLocation, Interaction::class.java) {
                        configureSeatHitbox(it, occupied = false)
                    }
                    entity = hitbox
                    seatBinding = SeatBinding(
                        hitbox = hitbox,
                        carrier = null,
                        ownerCar = ownerCar,
                        driverSeat = driverSeat,
                        controlEnd = controlEnd,
                        ensureCarrier = ::ensureCarrier,
                        destroyCarrier = { destroyCarrier() },
                        alignAfterDismount = ::alignAfterDismount
                    ).also { binding ->
                        SeatInteractionListener.register(hitbox.uniqueId, binding)
                    }
                }
            } finally {
                spawnScheduled.set(false)
            }
        }
    }

    fun refreshActivation(player: Player, playerLocation: Location = player.location) {
        if (retired) return
        if (!isEligible(player)) {
            deactivateFor(player)
            return
        }
        val playerId = player.uniqueId
        val radius = if (activePlayers.containsKey(playerId)) DEACTIVATE_DISTANCE else ACTIVATE_DISTANCE
        if (!isWithinDistance(playerLocation, radius)) {
            deactivateFor(player)
            return
        }
        activePlayers[playerId] = player
        if (entity == null) spawnEntity()
    }

    fun deactivateFor(player: Player) {
        if (activePlayers.remove(player.uniqueId) == null) return
        val packetMotion = BukkitPlugin.INSTANCE.packetEntityMotion
        entity?.let { packetMotion?.releaseViewer(it, player.uniqueId) }
        carrierEntity?.let { packetMotion?.releaseViewer(it, player.uniqueId) }
        if (activePlayers.isNotEmpty()) return
        destroyRuntimeEntities(force = false)
    }

    override fun syncEntity(entity: org.bukkit.entity.Entity, rootVelocity: Vector3dc) = Unit

    override fun updateState(
        parentRelativeTransform: CompactTransform,
        rootTransform: CompactTransform,
        rootVelocity: Vector3dc,
        transformDirty: Boolean
    ) {
        super.updateState(parentRelativeTransform, rootTransform, rootVelocity, transformDirty)
        if (activePlayers.isEmpty() && carrierEntity == null) {
            destroyRuntimeEntities(force = false)
            return
        }
        val hitbox = entity as? Interaction
        if (hitbox == null) {
            if (activePlayers.isNotEmpty()) spawnEntity()
            return
        }
        val visualLocation = entityLocation(hitbox)
        latestCarrierLocation = bottomCarrierLocation(visualLocation)
        val revision = movementRevision.incrementAndGet()
        val carrier = carrierEntity
        moveInteraction(hitbox, visualLocation, revision) {
            configureSeatHitbox(hitbox, occupied = carrier != null)
        }
        if (carrier != null) {
            moveCarrier(carrier, bottomCarrierLocation(visualLocation), revision)
        }
    }

    override fun collectDestruction(steps: DestructionSteps) {
        retired = true
        movementRevision.incrementAndGet()
        activePlayers.clear()
        steps.await { destroyRuntimeEntities(force = true) }
        pendingRemovals.forEach { pending -> steps.await { pending } }
        super.collectDestruction(steps)
    }

    @Synchronized
    private fun ensureCarrier(): Entity {
        check(!retired) { "Seat model is retired" }
        carrierEntity?.takeIf { it.isValid }?.let { return it }
        destroyCarrier()
        val hitbox = entity as? Interaction ?: error("Seat hitbox not initialized")
        val carrierLocation = latestCarrierLocation?.clone() ?: bottomCarrierLocation(hitbox.location)
        val carrier = world.bukkitWorldData.bukkitWorld.spawn(
            carrierLocation,
            ArmorStand::class.java,
            ::configureSeatCarrier
        )
        carrierEntity = carrier
        val binding = seatBinding ?: error("Seat binding not initialized")
        binding.carrier = carrier
        SeatInteractionListener.bindCarrier(binding, carrier)
        checkNotNull(BukkitPlugin.INSTANCE.packetEntityMotion) {
            "Packet entity motion is not initialized"
        }.registerSeatCarrier(carrier, carrierLocation, activePlayers.values)
        return carrier
    }

    @Synchronized
    private fun destroyCarrier(): CompletionStage<Void> {
        movementRevision.incrementAndGet()
        val binding = seatBinding
        val carrier = carrierEntity ?: binding?.carrier ?: return CompletableFuture.completedFuture(null)
        binding?.carrier = null
        carrierEntity = null
        val steps = DestructionSteps()
        // Remove the lookup using the captured carrier after detaching its binding.
        steps.action { SeatInteractionListener.unbindCarrier(carrier) }
        steps.action { BukkitPlugin.INSTANCE.packetEntityMotion?.release(carrier) }
        steps.await { BukkitPlugin.INSTANCE.platformDispatcher.removeEntityWithPassengers(carrier) }
        return trackRemoval(steps.finish())
    }

    @Synchronized
    private fun destroyRuntimeEntities(force: Boolean): CompletionStage<Void> {
        if (!force && carrierEntity != null) return CompletableFuture.completedFuture(null)
        movementRevision.incrementAndGet()
        val steps = DestructionSteps()
        steps.await { destroyCarrier() }
        val hitbox = entity as? Interaction
        entity = null
        seatBinding = null
        latestCarrierLocation = null
        if (hitbox != null) {
            steps.action { SeatInteractionListener.unregister(hitbox.uniqueId) }
            steps.action { BukkitPlugin.INSTANCE.packetEntityMotion?.release(hitbox) }
            steps.await { BukkitPlugin.INSTANCE.platformDispatcher.removeEntityWithPassengers(hitbox) }
        }
        return trackRemoval(steps.finish())
    }

    private fun trackRemoval(completion: CompletionStage<Void>): CompletionStage<Void> {
        pendingRemovals.removeAll { it.isDone && !it.isCompletedExceptionally }
        pendingRemovals += completion.toCompletableFuture()
        return completion
    }

    private fun alignAfterDismount(player: Player) {
        val target = latestCarrierLocation?.clone() ?: return
        BukkitPlugin.INSTANCE.platformDispatcher.runForEntityLater(player, 1L, action = {
            if (retired || !player.isOnline) return@runForEntityLater
            val current = player.location
            target.yaw = current.yaw
            target.pitch = current.pitch
            player.teleportAsync(target)
        })
    }

    private fun isEligible(player: Player): Boolean =
        player.isValid && !player.isDead && player.world == world.bukkitWorldData.bukkitWorld

    private fun isWithinDistance(playerLocation: Location, radius: Double): Boolean {
        val seatPosition = state.worldTransform.position
        val dx = playerLocation.x - seatPosition.x()
        val dy = playerLocation.y - seatPosition.y()
        val dz = playerLocation.z - seatPosition.z()
        return dx * dx + dy * dy + dz * dz <= radius * radius
    }

    private fun configureSeatHitbox(interaction: Interaction, occupied: Boolean) {
        configureCommonInteraction(interaction)
        interaction.setResponsive(!occupied)
        interaction.setInteractionWidth(if (occupied) 0.0f else 1.0f)
        interaction.setInteractionHeight(if (occupied) 0.0f else 1.0f)
    }

    private fun configureSeatCarrier(entity: ArmorStand) {
        entity.isPersistent = false
        entity.isSilent = true
        entity.isInvisible = true
        entity.isMarker = true
        entity.isSmall = true
        entity.setArms(false)
        entity.setBasePlate(false)
        entity.setGravity(false)
        entity.setNoPhysics(true)
        entity.isCollidable = false
    }

    private fun configureCommonInteraction(interaction: Interaction) {
        interaction.isPersistent = false
        interaction.isSilent = true
        interaction.setNoPhysics(true)
        interaction.setGravity(false)
    }

    private fun moveInteraction(
        interaction: Interaction,
        location: Location,
        revision: Long,
        afterMove: () -> Unit
    ) {
        BukkitPlugin.INSTANCE.platformDispatcher.runForEntity(interaction, action = entityTask@{
            if (revision != movementRevision.get() || !interaction.isValid) return@entityTask
            val viewersById = LinkedHashMap<UUID, Player>()
            activePlayers.values.forEach { viewersById[it.uniqueId] = it }
            interaction.passengers.filterIsInstance<Player>().forEach {
                viewersById[it.uniqueId] = it
            }
            checkNotNull(BukkitPlugin.INSTANCE.packetEntityMotion) {
                "Packet entity motion is not initialized"
            }.sync(interaction, location, viewersById.values)
            afterMove()
        })
    }

    private fun moveCarrier(carrier: Entity, location: Location, revision: Long) {
        val armorStand = carrier as? ArmorStand
            ?: error("Seat carrier must be an ArmorStand, got ${carrier.type}")
        val packetMotion = checkNotNull(BukkitPlugin.INSTANCE.packetEntityMotion) {
            "Packet entity motion is not initialized"
        }
        packetMotion.renderSeatCarrier(armorStand, location, activePlayers.values)
        BukkitPlugin.INSTANCE.platformDispatcher.runForEntity(armorStand, action = entityTask@{
            if (revision != movementRevision.get() || !armorStand.isValid) return@entityTask
            packetMotion.syncSeatAuthority(armorStand, location)
            configureSeatCarrier(armorStand)
        })
    }

    private fun bottomCarrierLocation(hitboxLocation: Location): Location = hitboxLocation.clone()

    companion object {
        // Vanilla interaction reach is below this radius; the extra margin avoids pop-in while moving.
        private const val ACTIVATE_DISTANCE = 6.0
        private const val DEACTIVATE_DISTANCE = 8.0
    }

    data class SeatBinding(
        val hitbox: Interaction,
        var carrier: Entity?,
        val ownerCar: Car?,
        val driverSeat: Boolean,
        val controlEnd: DriverControlEnd?,
        val ensureCarrier: () -> Entity,
        val destroyCarrier: () -> Unit,
        val alignAfterDismount: (Player) -> Unit
    )

    object SeatInteractionListener : Listener {
        private val seatsByHitbox = ConcurrentHashMap<UUID, SeatBinding>()
        private val seatsByCarrier = ConcurrentHashMap<UUID, SeatBinding>()
        private val driverSeatVacatedHandlers = CopyOnWriteArrayList<(Player, Train) -> Unit>()
        private var registered = false

        @Synchronized
        fun ensureRegistered() {
            if (registered) return
            Bukkit.getPluginManager().registerEvents(this, BukkitPlugin.INSTANCE)
            registered = true
        }

        /** Called after model destruction has queued owner cleanup, before releasing the runtime. */
        @Synchronized
        fun close() {
            HandlerList.unregisterAll(this)
            registered = false
            seatsByHitbox.clear()
            seatsByCarrier.clear()
            driverSeatVacatedHandlers.clear()
        }

        fun register(hitboxId: UUID, binding: SeatBinding) {
            seatsByHitbox[hitboxId] = binding
        }

        fun unregister(hitboxId: UUID) {
            val binding = seatsByHitbox.remove(hitboxId) ?: return
            binding.carrier?.let { seatsByCarrier.remove(it.uniqueId) }
        }

        fun bindCarrier(binding: SeatBinding, carrier: Entity) {
            binding.carrier?.let { seatsByCarrier.remove(it.uniqueId) }
            binding.carrier = carrier
            seatsByCarrier[carrier.uniqueId] = binding
        }

        fun unbindCarrier(carrier: Entity) {
            seatsByCarrier.remove(carrier.uniqueId)
        }

        fun addDriverSeatVacatedHandler(handler: (Player, Train) -> Unit): AutoCloseable {
            driverSeatVacatedHandlers.add(handler)
            return AutoCloseable { driverSeatVacatedHandlers.remove(handler) }
        }

        internal fun notifyDriverSeatVacated(player: Player, train: Train) {
            driverSeatVacatedHandlers.forEach { handler -> handler(player, train) }
        }

        fun drivingTrainFor(player: Player): Train? = driverSeatFor(player)?.train

        fun driverSeatFor(player: Player): DriverSeatContext? {
            val vehicle = player.vehicle ?: return null
            val binding = seatsByCarrier[vehicle.uniqueId] ?: return null
            if (!binding.driverSeat) return null
            val carrier = binding.carrier ?: return null
            if (!carrier.passengers.any { it.uniqueId == player.uniqueId }) return null
            val ownerCar = binding.ownerCar ?: return null
            val train = ownerCar.world.allTrains.firstOrNull { train -> train.cars.any { it === ownerCar } } ?: return null
            return DriverSeatContext(train, ownerCar, binding.controlEnd)
        }

        fun isPlayerDrivingTrain(player: Player, train: Train): Boolean {
            return drivingTrainFor(player) === train
        }

        data class DriverSeatContext(
            val train: Train,
            val car: Car,
            val controlEnd: DriverControlEnd?
        )

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onPlayerDismountSeat(event: EntityDismountEvent) {
            handleDismount(event, BukkitPlugin.INSTANCE.platformDispatcher)
        }

        internal fun handleDismount(event: EntityDismountEvent, dispatcher: work.art1st.javarailways.bukkit.util.PlatformDispatcher) {
            val player = event.entity as? Player ?: return
            val carrier = event.dismounted
            val binding = seatsByCarrier[carrier.uniqueId] ?: return
            binding.alignAfterDismount(player)
            dispatcher.runForEntityLater(carrier, 1L, action = {
                restoreIfEmpty(binding)
            })
            if (!binding.driverSeat) return
            val ownerCar = binding.ownerCar ?: return
            val train = ownerCar.world.allTrains.firstOrNull { train -> train.cars.any { it === ownerCar } } ?: return
            notifyDriverSeatVacated(player, train)
        }

        @EventHandler
        fun onPlayerQuit(event: PlayerQuitEvent) {
            restoreSeatFor(event.player)
        }

        @EventHandler
        fun onPlayerDeath(event: PlayerDeathEvent) {
            restoreSeatFor(event.player)
        }

        @EventHandler
        fun onPlayerInteractAtSeat(event: PlayerInteractAtEntityEvent) {
            val hitbox = event.rightClicked as? Interaction ?: return
            val binding = seatsByHitbox[hitbox.uniqueId] ?: return
            val carrier = binding.carrier?.takeIf { it.isValid } ?: binding.ensureCarrier()

            event.isCancelled = true
            if (carrier.passengers.isNotEmpty()) return

            val player: Player = event.player
            setSeatOccupied(binding, occupied = true)
            BukkitPlugin.INSTANCE.platformDispatcher.runForEntityLater(carrier, 1L, action = action@{
                if (
                    seatsByCarrier[carrier.uniqueId] !== binding ||
                    !carrier.isValid ||
                    carrier.passengers.isNotEmpty() ||
                    !BukkitThreadOwnership.isOwnedByCurrentRegion(player) ||
                    !player.isOnline
                ) {
                    restoreIfEmpty(binding)
                    return@action
                }
                if (!carrier.addPassenger(player)) {
                    restoreIfEmpty(binding)
                }
            })
        }

        private fun restoreSeatFor(player: Player) {
            val carrier = player.vehicle ?: return
            val binding = seatsByCarrier[carrier.uniqueId] ?: return
            BukkitPlugin.INSTANCE.platformDispatcher.runForEntityLater(carrier, 1L, action = {
                carrier.passengers.firstOrNull { it.uniqueId == player.uniqueId }?.let(carrier::removePassenger)
                restoreIfEmpty(binding)
            })
        }

        private fun restoreIfEmpty(binding: SeatBinding) {
            if (seatsByHitbox[binding.hitbox.uniqueId] !== binding) return
            val carrier = binding.carrier
            if (carrier == null) {
                setSeatOccupied(binding, occupied = false)
                return
            }
            if (!carrier.isValid || carrier.passengers.isEmpty() ||
                carrier.passengers.filterIsInstance<Player>().none { it.isOnline && !it.isDead }
            ) {
                binding.destroyCarrier()
                setSeatOccupied(binding, occupied = false)
            }
        }

        private fun setSeatOccupied(binding: SeatBinding, occupied: Boolean) {
            BukkitPlugin.INSTANCE.platformDispatcher.runForEntity(binding.hitbox, action = {
                if (!binding.hitbox.isValid) return@runForEntity
                binding.hitbox.setResponsive(!occupied)
                binding.hitbox.setInteractionWidth(if (occupied) 0.0f else 1.0f)
                binding.hitbox.setInteractionHeight(if (occupied) 0.0f else 1.0f)
            })
        }
    }
}
