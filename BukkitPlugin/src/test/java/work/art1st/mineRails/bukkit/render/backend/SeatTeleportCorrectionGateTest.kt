package work.art1st.mineRails.bukkit.render.backend

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class SeatTeleportCorrectionGateTest {
    @Test
    fun `matching correction is consumed once`() {
        val gate = SeatTeleportCorrectionGate(expiryNanos = 1_000L)
        val playerId = UUID.randomUUID()
        gate.mark(playerId, PacketPosition(10.0, 20.0, 30.0), nowNanos = 100L)

        assertTrue(gate.consumeIfMatches(playerId, PacketPosition(10.0, 19.4, 30.0), nowNanos = 200L))
        assertFalse(gate.consumeIfMatches(playerId, PacketPosition(10.0, 19.4, 30.0), nowNanos = 201L))
    }

    @Test
    fun `unrelated player teleport is not consumed`() {
        val gate = SeatTeleportCorrectionGate(expiryNanos = 1_000L)
        val playerId = UUID.randomUUID()
        gate.mark(playerId, PacketPosition(10.0, 20.0, 30.0), nowNanos = 100L)

        assertFalse(gate.consumeIfMatches(playerId, PacketPosition(100.0, 80.0, 100.0), nowNanos = 200L))
        assertTrue(gate.consumeIfMatches(playerId, PacketPosition(10.0, 20.0, 30.0), nowNanos = 201L))
    }

    @Test
    fun `expired correction is not consumed`() {
        val gate = SeatTeleportCorrectionGate(expiryNanos = 100L)
        val playerId = UUID.randomUUID()
        gate.mark(playerId, PacketPosition(10.0, 20.0, 30.0), nowNanos = 100L)

        assertFalse(gate.consumeIfMatches(playerId, PacketPosition(10.0, 20.0, 30.0), nowNanos = 201L))
    }

    @Test
    fun `old completion cannot clear a newer marker`() {
        val gate = SeatTeleportCorrectionGate(expiryNanos = 1_000L)
        val playerId = UUID.randomUUID()
        val old = gate.mark(playerId, PacketPosition(10.0, 20.0, 30.0), nowNanos = 100L)
        gate.mark(playerId, PacketPosition(20.0, 20.0, 30.0), nowNanos = 110L)

        gate.clear(old)

        assertTrue(gate.consumeIfMatches(playerId, PacketPosition(20.0, 20.0, 30.0), nowNanos = 120L))
    }
}
