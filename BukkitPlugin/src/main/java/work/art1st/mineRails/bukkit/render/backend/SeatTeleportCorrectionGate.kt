package work.art1st.mineRails.bukkit.render.backend

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal data class PacketPosition(val x: Double, val y: Double, val z: Double)

internal class SeatTeleportMarker internal constructor(
    val playerId: UUID,
    internal val sequence: Long
)

/** Correlates a short-lived player correction with one managed Seat carrier teleport. */
internal class SeatTeleportCorrectionGate(
    private val expiryNanos: Long = DEFAULT_EXPIRY_NANOS
) {
    private val sequence = AtomicLong()
    private val pending = ConcurrentHashMap<UUID, Pending>()

    fun mark(playerId: UUID, target: PacketPosition, nowNanos: Long = System.nanoTime()): SeatTeleportMarker {
        val marker = SeatTeleportMarker(playerId, sequence.incrementAndGet())
        pending[playerId] = Pending(marker.sequence, target, nowNanos + expiryNanos)
        return marker
    }

    fun consumeIfMatches(
        playerId: UUID,
        correction: PacketPosition,
        nowNanos: Long = System.nanoTime()
    ): Boolean {
        var consumed = false
        pending.compute(playerId) { _, candidate ->
            when {
                candidate == null -> null
                nowNanos > candidate.expiresAtNanos -> null
                candidate.target.distanceSquared(correction) <= MAX_CORRECTION_DISTANCE_SQUARED -> {
                    consumed = true
                    null
                }
                else -> candidate
            }
        }
        return consumed
    }

    fun clear(marker: SeatTeleportMarker) {
        pending.computeIfPresent(marker.playerId) { _, candidate ->
            if (candidate.sequence == marker.sequence) null else candidate
        }
    }

    fun clearAll() {
        pending.clear()
    }

    private fun PacketPosition.distanceSquared(other: PacketPosition): Double {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return dx * dx + dy * dy + dz * dz
    }

    private data class Pending(
        val sequence: Long,
        val target: PacketPosition,
        val expiresAtNanos: Long
    )

    private companion object {
        const val DEFAULT_EXPIRY_NANOS = 5_000_000_000L
        const val MAX_CORRECTION_DISTANCE_SQUARED = 3.0 * 3.0
    }
}
