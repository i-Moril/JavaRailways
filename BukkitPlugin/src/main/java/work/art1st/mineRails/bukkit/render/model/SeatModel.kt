package work.art1st.mineRails.bukkit.render.model

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.joml.Vector3dc
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.config.model.DriverControlEnd
import work.art1st.mineRails.bukkit.extension.bukkitWorldData
import work.art1st.mineRails.bukkit.render.Animation
import work.art1st.mineRails.bukkit.util.BukkitThreadOwnership
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.train.Car
import work.art1st.mineRails.core.api.physics.train.Train
import java.util.UUID
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
    private var carrierEntity: Interaction? = null
    @Volatile
    private var seatBinding: SeatBinding? = null
    private val visualLocationDelay: ArrayDeque<Location> = ArrayDeque()
    private val activePlayers: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val movementRevision = AtomicLong(0L)
    private val spawnScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile
    private var retired = false

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
                    val hitbox = bukkitWorld.spawn(spawnLocation, Interaction::class.java)
                    configureSeatHitbox(hitbox, occupied = false)
                    entity = hitbox
                    seatBinding = SeatBinding(
                        hitbox = hitbox,
                        carrier = null,
                        ownerCar = ownerCar,
                        driverSeat = driverSeat,
                        controlEnd = controlEnd,
                        ensureCarrier = ::ensureCarrier,
                        destroyCarrier = ::destroyCarrier
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
        if (!isEligible(player)) {
            deactivateFor(player)
            return
        }
        val playerId = player.uniqueId
        val radius = if (playerId in activePlayers) DEACTIVATE_DISTANCE else ACTIVATE_DISTANCE
        if (!isWithinDistance(playerLocation, radius)) {
            deactivateFor(player)
            return
        }
        activePlayers.add(playerId)
        if (entity == null) spawnEntity()
    }

    fun deactivateFor(player: Player) {
        if (!activePlayers.remove(player.uniqueId) || activePlayers.isNotEmpty()) return
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
        val visualLocation = delayedVisualLocation(entityLocation(hitbox))
        val revision = movementRevision.incrementAndGet()
        moveInteraction(hitbox, visualLocation, revision) {
            val carrier = carrierEntity
            configureSeatHitbox(hitbox, occupied = carrier != null)
            if (carrier != null) {
                moveInteraction(carrier, bottomCarrierLocation(visualLocation), revision) {
                    configureSeatCarrier(carrier)
                }
            }
        }
    }

    override fun destroy() {
        retired = true
        movementRevision.incrementAndGet()
        activePlayers.clear()
        destroyRuntimeEntities(force = true)
        visualLocationDelay.clear()
        super.destroy()
    }

    @Synchronized
    private fun ensureCarrier(): Interaction {
        carrierEntity?.takeIf { it.isValid }?.let { return it }
        destroyCarrier()
        val hitbox = entity as? Interaction ?: error("Seat hitbox not initialized")
        val carrier = world.bukkitWorldData.bukkitWorld.spawn(bottomCarrierLocation(hitbox.location), Interaction::class.java)
        configureSeatCarrier(carrier)
        carrierEntity = carrier
        val binding = seatBinding ?: error("Seat binding not initialized")
        binding.carrier = carrier
        SeatInteractionListener.bindCarrier(binding, carrier)
        return carrier
    }

    @Synchronized
    private fun destroyCarrier() {
        val binding = seatBinding
        val carrier = carrierEntity ?: binding?.carrier ?: return
        if (binding != null) SeatInteractionListener.unbindCarrier(binding)
        binding?.carrier = null
        carrierEntity = null
        BukkitPlugin.INSTANCE.platformDispatcher.runForEntity(carrier, action = {
            carrier.eject()
            carrier.remove()
        })
    }

    @Synchronized
    private fun destroyRuntimeEntities(force: Boolean) {
        if (!force && carrierEntity != null) return
        movementRevision.incrementAndGet()
        destroyCarrier()
        (entity as? Interaction)?.let { hitbox ->
            SeatInteractionListener.unregister(hitbox.uniqueId)
            BukkitPlugin.INSTANCE.platformDispatcher.runForEntity(hitbox, action = {
                hitbox.eject()
                hitbox.remove()
            })
        }
        entity = null
        seatBinding = null
        visualLocationDelay.clear()
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

    private fun configureSeatCarrier(interaction: Interaction) {
        configureCommonInteraction(interaction)
        interaction.setResponsive(false)
        interaction.setInteractionWidth(0.01f)
        interaction.setInteractionHeight(0.01f)
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
            if (BukkitPlugin.INSTANCE.platformCapabilities.folia) {
                interaction.teleportAsync(location).thenAccept { teleported ->
                    if (teleported) {
                        BukkitPlugin.INSTANCE.platformDispatcher.runForEntity(interaction, action = movedTask@{
                            if (revision != movementRevision.get()) return@movedTask
                            afterMove()
                        })
                    }
                }
            } else {
                interaction.teleport(location)
                afterMove()
            }
        })
    }

    private fun delayedVisualLocation(current: Location): Location {
        visualLocationDelay.addLast(current.clone())
        while (visualLocationDelay.size > SEAT_VISUAL_DELAY_TICKS + 1) {
            visualLocationDelay.removeFirst()
        }
        return if (visualLocationDelay.size > SEAT_VISUAL_DELAY_TICKS) {
            visualLocationDelay.first().clone()
        } else {
            current.clone()
        }
    }

    private fun bottomCarrierLocation(hitboxLocation: Location): Location {
        return hitboxLocation.clone()
    }

    companion object {
        private const val SEAT_VISUAL_DELAY_TICKS = 1
        // Vanilla interaction reach is below this radius; the extra margin avoids pop-in while moving.
        private const val ACTIVATE_DISTANCE = 6.0
        private const val DEACTIVATE_DISTANCE = 8.0
    }

    data class SeatBinding(
        val hitbox: Interaction,
        var carrier: Interaction?,
        val ownerCar: Car?,
        val driverSeat: Boolean,
        val controlEnd: DriverControlEnd?,
        val ensureCarrier: () -> Interaction,
        val destroyCarrier: () -> Unit
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

        fun register(hitboxId: UUID, binding: SeatBinding) {
            seatsByHitbox[hitboxId] = binding
        }

        fun unregister(hitboxId: UUID) {
            val binding = seatsByHitbox.remove(hitboxId) ?: return
            binding.carrier?.let { seatsByCarrier.remove(it.uniqueId) }
        }

        fun bindCarrier(binding: SeatBinding, carrier: Interaction) {
            binding.carrier?.let { seatsByCarrier.remove(it.uniqueId) }
            binding.carrier = carrier
            seatsByCarrier[carrier.uniqueId] = binding
        }

        fun unbindCarrier(binding: SeatBinding) {
            binding.carrier?.let { seatsByCarrier.remove(it.uniqueId) }
        }

        fun addDriverSeatVacatedHandler(handler: (Player, Train) -> Unit) {
            driverSeatVacatedHandlers.add(handler)
        }

        fun drivingTrainFor(player: Player): Train? = driverSeatFor(player)?.train

        fun driverSeatFor(player: Player): DriverSeatContext? {
            val vehicle = player.vehicle as? Interaction ?: return null
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

        @EventHandler
        fun onPlayerDismountSeat(event: EntityDismountEvent) {
            val player = event.entity as? Player ?: return
            val carrier = event.dismounted as? Interaction ?: return
            val binding = seatsByCarrier[carrier.uniqueId] ?: return
            BukkitPlugin.INSTANCE.platformDispatcher.runForEntityLater(carrier, 1L, action = {
                restoreIfEmpty(binding)
            })
            if (!binding.driverSeat) return
            val ownerCar = binding.ownerCar ?: return
            val train = ownerCar.world.allTrains.firstOrNull { train -> train.cars.any { it === ownerCar } } ?: return
            driverSeatVacatedHandlers.forEach { handler -> handler(player, train) }
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
            val carrier = player.vehicle as? Interaction ?: return
            val binding = seatsByCarrier[carrier.uniqueId] ?: return
            BukkitPlugin.INSTANCE.platformDispatcher.runForEntityLater(carrier, 1L, action = {
                carrier.passengers.firstOrNull { it.uniqueId == player.uniqueId }?.let(carrier::removePassenger)
                restoreIfEmpty(binding)
            })
        }

        private fun restoreIfEmpty(binding: SeatBinding) {
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
