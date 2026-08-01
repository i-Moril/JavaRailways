package work.art1st.javarailways.bukkit.util

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PlayerOperationGate {
    private val pendingByPlayer = ConcurrentHashMap<UUID, String>()

    fun tryStart(playerId: UUID, operation: String): String? = pendingByPlayer.putIfAbsent(playerId, operation)

    fun finish(playerId: UUID) {
        pendingByPlayer.remove(playerId)
    }

    fun clear(playerId: UUID) {
        pendingByPlayer.remove(playerId)
    }
}
