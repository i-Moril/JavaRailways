package work.art1st.javarailways.bukkit.render.backend

import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.MoverType
import net.minecraft.world.phys.Vec3
import net.minecraft.server.network.ServerGamePacketListenerImpl
import java.lang.invoke.MethodHandles
import org.bukkit.Location
import org.bukkit.craftbukkit.entity.CraftEntity
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import kotlin.math.sqrt

/** Moves the hidden server authority on its owning Paper/Folia entity thread without teleporting it. */
internal object SeatAuthorityMover {
    private val awaitingPosition = MethodHandles.privateLookupIn(ServerGamePacketListenerImpl::class.java, MethodHandles.lookup())
        .findVarHandle(ServerGamePacketListenerImpl::class.java, "awaitingPositionFromClient", Vec3::class.java)

    /** Read only on the player owner. Native code owns and processes every teleport confirmation. */
    fun hasPendingTeleport(player: Player): Boolean =
        awaitingPosition.get((player as CraftPlayer).handle.connection) != null

    /** Controlled walking exits already know the exact feet position. Move the mounted rider
     * there, then detach through the native event/passenger path without stopRiding's additional
     * DISMOUNT teleport and its wait for a client teleport confirmation. Ordinary seats and
     * lifecycle ejects continue using their existing native stopRiding behavior.
     */
    fun dismountAt(entity: Entity, player: Player, carrierTarget: Location): Boolean {
        move(entity, carrierTarget)
        val passenger = (player as CraftPlayer).handle
        passenger.removeVehicle()
        if (passenger.vehicle === (entity as CraftEntity).handle) return false
        passenger.connection.resetPosition()
        return true
    }

    fun move(entity: Entity, target: Location): SeatAuthorityMoveResult {
        val current = entity.location
        require(current.world === target.world) {
            "Seat authority cannot move directly between worlds"
        }

        val currentPoint = SeatAuthorityPoint(current.x, current.y, current.z)
        val targetPoint = SeatAuthorityPoint(target.x, target.y, target.z)
        val plan = SeatAuthorityMovePlan.between(currentPoint, targetPoint)
        val handle = (entity as? CraftEntity)?.handle
            ?: error("Seat authority is not backed by CraftEntity: ${entity.javaClass.name}")

        // ArmorStand.travel() intentionally ignores velocity while marker or no-gravity is set.
        // Entity.move() remains valid and, with noPhysics=true, performs an exact position update
        // while still notifying the entity section manager through setPos()/onMove().
        handle.move(MoverType.SELF, Vec3(plan.dx, plan.dy, plan.dz))
        handle.setDeltaMovement(Vec3.ZERO)
        handle.setYRot(target.yaw)
        handle.setXRot(0.0f)
        if (handle is LivingEntity) {
            handle.setYBodyRot(target.yaw)
            handle.setYHeadRot(target.yaw)
        }

        // Do not wait for the passenger's next rideTick: keep its authoritative server position in
        // the same frame as the carrier so audience tracking cannot drop the occupied Seat.
        handle.passengers.toList().forEach(handle::positionRider)

        val passengerEntityIds = handle.passengers.map { it.id }
        val actual = handle.position()
        val actualPoint = SeatAuthorityPoint(actual.x, actual.y, actual.z)
        return SeatAuthorityMoveResult(
            start = currentPoint,
            target = targetPoint,
            actual = actualPoint,
            residualError = SeatAuthorityMovePlan.residual(actualPoint, targetPoint),
            passengerEntityIds = passengerEntityIds
        )
    }
}

internal data class SeatAuthorityPoint(
    val x: Double,
    val y: Double,
    val z: Double
)

internal data class SeatAuthorityMovePlan(
    val dx: Double,
    val dy: Double,
    val dz: Double
) {
    companion object {
        fun between(current: SeatAuthorityPoint, target: SeatAuthorityPoint) = SeatAuthorityMovePlan(
            dx = target.x - current.x,
            dy = target.y - current.y,
            dz = target.z - current.z
        )

        fun residual(actual: SeatAuthorityPoint, target: SeatAuthorityPoint): Double {
            val dx = target.x - actual.x
            val dy = target.y - actual.y
            val dz = target.z - actual.z
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
    }
}

internal data class SeatAuthorityMoveResult(
    val start: SeatAuthorityPoint,
    val target: SeatAuthorityPoint,
    val actual: SeatAuthorityPoint,
    val residualError: Double,
    val passengerEntityIds: List<Int>
)
