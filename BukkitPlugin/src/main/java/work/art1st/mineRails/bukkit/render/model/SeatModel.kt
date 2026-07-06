package work.art1st.mineRails.bukkit.render.model

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.joml.Vector3dc
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.extension.bukkitWorldData
import work.art1st.mineRails.bukkit.render.Animation
import work.art1st.mineRails.core.api.physics.World
import java.util.UUID

class SeatModel internal constructor(
    world: World,
    state: ModelState,
    attachedModels: Collection<AbstractModel>,
    animation: Map<String, Animation>,
    private val initialLocation: Location
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
        SeatInteractionListener.register(hitbox.uniqueId, carrier)
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

    private object SeatInteractionListener : Listener {
        private val seatCarriers = mutableMapOf<UUID, Interaction>()
        private var registered = false

        fun ensureRegistered() {
            if (registered) return
            Bukkit.getPluginManager().registerEvents(this, BukkitPlugin.INSTANCE)
            registered = true
        }

        fun register(hitboxId: UUID, carrier: Interaction) {
            seatCarriers[hitboxId] = carrier
        }

        fun unregister(hitboxId: UUID) {
            seatCarriers.remove(hitboxId)
        }

        @EventHandler
        fun onPlayerInteractAtSeat(event: PlayerInteractAtEntityEvent) {
            val hitbox = event.rightClicked as? Interaction ?: return
            val carrier = seatCarriers[hitbox.uniqueId] ?: return

            event.isCancelled = true
            if (!carrier.isValid || carrier.passengers.isNotEmpty()) return

            val player: Player = event.player
            carrier.addPassenger(player)
        }
    }
}
