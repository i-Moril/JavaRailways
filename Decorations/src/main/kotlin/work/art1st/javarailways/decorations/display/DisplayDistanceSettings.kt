package work.art1st.javarailways.decorations.display

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Session-only preferences; safe to query from the host's render workers. */
class DisplayDistanceSettings(val defaultBlocks: Double = 32.0, val maxBlocks: Double = 128.0) {
    private val preferences = ConcurrentHashMap<UUID, Double>()

    init {
        require(maxBlocks.isFinite() && maxBlocks > 0 && maxBlocks <= 4096) {
            "displays.interior-distance.max-blocks must be finite and in (0, 4096]"
        }
        require(defaultBlocks.isFinite() && defaultBlocks >= 0 && defaultBlocks <= maxBlocks) {
            "displays.interior-distance.default-blocks must be finite and in [0, $maxBlocks]"
        }
    }

    fun distanceFor(viewerId: UUID): Double = preferences[viewerId] ?: defaultBlocks

    fun set(viewerId: UUID, blocks: Double) {
        require(blocks.isFinite() && blocks >= 0 && blocks <= maxBlocks) {
            "Distance must be finite and in [0, $maxBlocks]"
        }
        preferences[viewerId] = blocks
    }

    fun reset(viewerId: UUID) { preferences.remove(viewerId) }
    fun clear() { preferences.clear() }
}
