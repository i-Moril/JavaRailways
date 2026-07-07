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
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.train.Car
import work.art1st.mineRails.core.api.physics.train.Train
import java.util.UUID

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
    private var carrierEntity: Interaction? = null
    private val visualLocationDelay: ArrayDeque<Location> = ArrayDeque()

    init {
        SeatInteractionListener.ensureRegistered()
        spawnEntity()
    }

    override fun spawnEntity() {
        val bukkitWorld = world.bukkitWorldData.bukkitWorld
        val hitbox = bukkitWorld.spawn(initialLocation, Interaction::class.java)
        configureSeatHitbox(hitbox, occupied = false)

        val carrier = bukkitWorld.spawn(bottomCarrierLocation(hitbox.location), Interaction::class.java)
        configureSeatCarrier(carrier)

        entity = hitbox
        carrierEntity = carrier
        SeatInteractionListener.register(hitbox.uniqueId, SeatBinding(hitbox, carrier, ownerCar, driverSeat, controlEnd))
    }

    override fun updateState(
        parentRelativeTransform: CompactTransform,
        rootTransform: CompactTransform,
        rootVelocity: Vector3dc,
        transformDirty: Boolean
    ) {
        super.updateState(parentRelativeTransform, rootTransform, rootVelocity, transformDirty)
        val hitbox = entity as? Interaction ?: return
        val carrier = carrierEntity ?: return
        val visualLocation = delayedVisualLocation(hitbox.location)
        hitbox.teleport(visualLocation)
        configureSeatHitbox(hitbox, occupied = carrier.passengers.isNotEmpty())
        configureSeatCarrier(carrier)
        carrier.teleport(bottomCarrierLocation(visualLocation))
    }

    override fun destroy() {
        (entity as? Interaction)?.let { hitbox ->
            SeatInteractionListener.unregister(hitbox.uniqueId)
            hitbox.eject()
        }
        carrierEntity?.let { carrier ->
            carrier.eject()
            carrier.remove()
        }
        carrierEntity = null
        visualLocationDelay.clear()
        super.destroy()
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
        // Bukkit entity locations are the bottom-center of the entity hitbox.
        // Keep the actual vehicle there, with zero size, so passengers do not sit
        // on top of the 1-block interaction hitbox.
        return hitboxLocation.clone()
    }

    companion object {
        private const val SEAT_VISUAL_DELAY_TICKS = 1
    }

    data class SeatBinding(
        val hitbox: Interaction,
        val carrier: Interaction,
        val ownerCar: Car?,
        val driverSeat: Boolean,
        val controlEnd: DriverControlEnd?
    )

    object SeatInteractionListener : Listener {
        private val seatsByHitbox = mutableMapOf<UUID, SeatBinding>()
        private val seatsByCarrier = mutableMapOf<UUID, SeatBinding>()
        private val driverSeatVacatedHandlers = mutableListOf<(Player, Train) -> Unit>()
        private var registered = false

        fun ensureRegistered() {
            if (registered) return
            Bukkit.getPluginManager().registerEvents(this, BukkitPlugin.INSTANCE)
            registered = true
        }

        fun register(hitboxId: UUID, binding: SeatBinding) {
            seatsByHitbox[hitboxId] = binding
            seatsByCarrier[binding.carrier.uniqueId] = binding
        }

        fun unregister(hitboxId: UUID) {
            val binding = seatsByHitbox.remove(hitboxId) ?: return
            seatsByCarrier.remove(binding.carrier.uniqueId)
        }

        fun addDriverSeatVacatedHandler(handler: (Player, Train) -> Unit) {
            driverSeatVacatedHandlers.add(handler)
        }

        fun drivingTrainFor(player: Player): Train? = driverSeatFor(player)?.train

        fun driverSeatFor(player: Player): DriverSeatContext? {
            val vehicle = player.vehicle as? Interaction ?: return null
            val binding = seatsByCarrier[vehicle.uniqueId] ?: return null
            if (!binding.driverSeat) return null
            if (!binding.carrier.passengers.any { it.uniqueId == player.uniqueId }) return null
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
            Bukkit.getScheduler().runTask(BukkitPlugin.INSTANCE, Runnable { restoreIfEmpty(binding) })
            if (!binding.driverSeat) return
            val ownerCar = binding.ownerCar ?: return
            val train = ownerCar.world.allTrains.firstOrNull { train -> train.cars.any { it === ownerCar } } ?: return
            driverSeatVacatedHandlers.forEach { handler -> handler(player, train) }
        }

        @EventHandler
        fun onPlayerQuit(event: PlayerQuitEvent) {
            Bukkit.getScheduler().runTask(BukkitPlugin.INSTANCE, Runnable { restoreSeatsFor(event.player.uniqueId) })
        }

        @EventHandler
        fun onPlayerDeath(event: PlayerDeathEvent) {
            Bukkit.getScheduler().runTask(BukkitPlugin.INSTANCE, Runnable { restoreSeatsFor(event.player.uniqueId) })
        }

        @EventHandler
        fun onPlayerInteractAtSeat(event: PlayerInteractAtEntityEvent) {
            val hitbox = event.rightClicked as? Interaction ?: return
            val binding = seatsByHitbox[hitbox.uniqueId] ?: return
            val carrier = binding.carrier

            event.isCancelled = true
            if (!carrier.isValid || carrier.passengers.isNotEmpty()) return

            val player: Player = event.player
            setSeatOccupied(binding, occupied = true)
            Bukkit.getScheduler().runTask(BukkitPlugin.INSTANCE, Runnable {
                if (!player.isOnline || !carrier.isValid || carrier.passengers.isNotEmpty()) {
                    restoreIfEmpty(binding)
                    return@Runnable
                }
                if (!carrier.addPassenger(player)) {
                    restoreIfEmpty(binding)
                }
            })
        }

        private fun restoreSeatsFor(playerId: UUID) {
            seatsByCarrier.values.forEach { binding ->
                binding.carrier.passengers.firstOrNull { it.uniqueId == playerId }?.let { binding.carrier.removePassenger(it) }
                restoreIfEmpty(binding)
            }
        }

        private fun restoreIfEmpty(binding: SeatBinding) {
            if (!binding.carrier.isValid || binding.carrier.passengers.isEmpty() ||
                binding.carrier.passengers.filterIsInstance<Player>().none { it.isOnline && !it.isDead }
            ) {
                setSeatOccupied(binding, occupied = false)
            }
        }

        private fun setSeatOccupied(binding: SeatBinding, occupied: Boolean) {
            if (!binding.hitbox.isValid) return
            binding.hitbox.setResponsive(!occupied)
            binding.hitbox.setInteractionWidth(if (occupied) 0.0f else 1.0f)
            binding.hitbox.setInteractionHeight(if (occupied) 0.0f else 1.0f)
        }
    }
}
