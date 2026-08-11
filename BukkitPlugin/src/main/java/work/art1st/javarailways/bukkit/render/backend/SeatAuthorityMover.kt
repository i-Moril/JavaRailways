package work.art1st.javarailways.bukkit.render.backend

import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.MoverType
import net.minecraft.world.phys.Vec3
import org.bukkit.Location
import org.bukkit.craftbukkit.entity.CraftEntity
import org.bukkit.entity.Entity
import kotlin.math.sqrt

/** Moves the hidden server authority on its owning Paper/Folia entity thread without teleporting it. */
internal object SeatAuthorityMover {
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
