package work.art1st.mineRails.packet

import com.github.retrooper.packetevents.protocol.entity.type.EntityType
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import java.util.Optional
import java.util.UUID

/**
 * Builds and sends entity spawn packets via PacketEvents API.
 * Supports position, rotation, and riding (vehicle/passenger) relationship.
 * No Bukkit dependency; uses [User] for sending.
 */
class PacketEntity(
    /** Unique entity id for this packet entity (used in spawn and passenger packets). */
    var entityId: Int,
    /** Entity type to spawn. */
    var entityType: EntityType,
    /** Spawn position (x, y, z). */
    var position: Vector3d = Vector3d.zero(),
    /** Pitch angle in degrees. */
    var pitch: Float = 0f,
    /** Yaw angle in degrees. */
    var yaw: Float = 0f,
    /** Head yaw in degrees. */
    var headYaw: Float = 0f,
    /** Optional UUID for the entity. */
    var uuid: Optional<UUID> = Optional.empty(),
    /** Entity data (type-specific, e.g. object data for falling blocks). Default 0. */
    var data: Int = 0,
    /** Optional initial velocity. */
    var velocity: Optional<Vector3d> = Optional.empty(),
) {
    /**
     * If set, this entity is a passenger of the vehicle with this entity id.
     * After spawn, a [WrapperPlayServerSetPassengers] is sent for that vehicle including this entity.
     */
    var vehicleId: Int? = null

    /**
     * Entity ids of passengers riding this entity.
     * After spawn, a [WrapperPlayServerSetPassengers] is sent for this entity with these ids.
     */
    var passengerIds: IntArray = IntArray(0)

    /**
     * Sends spawn and passenger packets to the given [User] using PacketEvents.
     * Order: spawn this entity, then if [vehicleId] is set send SetPassengers(vehicleId, [this entity]),
     * then if [passengerIds] is not empty send SetPassengers(this entity, passengerIds).
     */
    fun send(user: User) {
        val spawnPacket = WrapperPlayServerSpawnEntity(
            entityId,
            uuid,
            entityType,
            position,
            pitch,
            yaw,
            headYaw,
            data,
            velocity,
        )
        user.sendPacket(spawnPacket)

        vehicleId?.let { vid ->
            val passengers = intArrayOf(entityId)
            user.sendPacket(WrapperPlayServerSetPassengers(vid, passengers))
        }

        if (passengerIds.isNotEmpty()) {
            user.sendPacket(WrapperPlayServerSetPassengers(entityId, passengerIds))
        }
    }

    /** Sets position from (x, y, z). */
    fun setPosition(x: Double, y: Double, z: Double) {
        position = Vector3d(x, y, z)
    }

    /** Sets this entity as passenger of the vehicle with the given entity id. */
    fun setVehicle(vehicleEntityId: Int) {
        vehicleId = vehicleEntityId
    }

    /** Sets the entity ids of passengers riding this entity. */
    fun setPassengers(passengerEntityIds: IntArray) {
        passengerIds = passengerEntityIds
    }
}
