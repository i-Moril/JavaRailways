package work.art1st.mineRails.bukkit.render.model

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.joml.Vector3dc
import work.art1st.mineRails.bukkit.BukkitPlugin
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
    private val driverSeat: Boolean
) : EntityModel(world, state, attachedModels, animation, null) {
    private var carrierEntity: Interaction? = null

    init {
        SeatInteractionListener.ensureRegistered()
        spawnEntity()
    }

    override fun spawnEntity() {
        val bukkitWorld = world.bukkitWorldData.bukkitWorld
        val hitbox = bukkitWorld.spawn(initialLocation, Interaction::class.java)
        configureSeatHitbox(hitbox)

        val carrier = bukkitWorld.spawn(bottomCarrierLocation(hitbox.location), Interaction::class.java)
        configureSeatCarrier(carrier)

        entity = hitbox
        carrierEntity = carrier
        SeatInteractionListener.register(hitbox.uniqueId, SeatBinding(carrier, ownerCar, driverSeat))
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
        configureSeatHitbox(hitbox)
        configureSeatCarrier(carrier)
        carrier.teleport(bottomCarrierLocation(hitbox.location))
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
        super.destroy()
    }

    private fun configureSeatHitbox(interaction: Interaction) {
        configureCommonInteraction(interaction)
        interaction.setResponsive(true)
        interaction.setInteractionWidth(1.0f)
        interaction.setInteractionHeight(1.0f)
    }

    private fun configureSeatCarrier(interaction: Interaction) {
        configureCommonInteraction(interaction)
        interaction.setResponsive(false)
        interaction.setInteractionWidth(0.0f)
        interaction.setInteractionHeight(0.0f)
    }

    private fun configureCommonInteraction(interaction: Interaction) {
        interaction.isPersistent = false
        interaction.isSilent = true
        interaction.setNoPhysics(true)
        interaction.setGravity(false)
    }

    private fun bottomCarrierLocation(hitboxLocation: Location): Location {
        // Bukkit entity locations are the bottom-center of the entity hitbox.
        // Keep the actual vehicle there, with zero size, so passengers do not sit
        // on top of the 1-block interaction hitbox.
        return hitboxLocation.clone()
    }

    data class SeatBinding(
        val carrier: Interaction,
        val ownerCar: Car?,
        val driverSeat: Boolean
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

        fun drivingTrainFor(player: Player): Train? {
            val vehicle = player.vehicle as? Interaction ?: return null
            val binding = seatsByCarrier[vehicle.uniqueId] ?: return null
            if (!binding.driverSeat) return null
            if (!binding.carrier.passengers.any { it.uniqueId == player.uniqueId }) return null
            val ownerCar = binding.ownerCar ?: return null
            return ownerCar.world.allTrains.firstOrNull { train -> train.cars.any { it === ownerCar } }
        }

        fun isPlayerDrivingTrain(player: Player, train: Train): Boolean {
            return drivingTrainFor(player) === train
        }

        @EventHandler
        fun onPlayerDismountSeat(event: EntityDismountEvent) {
            val player = event.entity as? Player ?: return
            val carrier = event.dismounted as? Interaction ?: return
            val binding = seatsByCarrier[carrier.uniqueId] ?: return
            if (!binding.driverSeat) return
            val ownerCar = binding.ownerCar ?: return
            val train = ownerCar.world.allTrains.firstOrNull { train -> train.cars.any { it === ownerCar } } ?: return
            driverSeatVacatedHandlers.forEach { handler -> handler(player, train) }
        }

        @EventHandler
        fun onPlayerInteractAtSeat(event: PlayerInteractAtEntityEvent) {
            val hitbox = event.rightClicked as? Interaction ?: return
            val binding = seatsByHitbox[hitbox.uniqueId] ?: return
            val carrier = binding.carrier

            event.isCancelled = true
            if (!carrier.isValid || carrier.passengers.isNotEmpty()) return

            val player: Player = event.player
            carrier.addPassenger(player)
        }
    }
}
