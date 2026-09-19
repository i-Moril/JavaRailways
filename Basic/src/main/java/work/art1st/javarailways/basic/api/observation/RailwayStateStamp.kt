package work.art1st.javarailways.basic.api.observation

/**
 * Identifies one coherent railway observation without conflating independent revision domains.
 *
 * [worldId] is the stable identifier assigned by the platform adapter. [physicsEpoch] identifies
 * the completed physics publication used by the observation, while [topologyRevision] identifies
 * the frozen rail topology against which positions and routes were resolved. Neither counter is a
 * persistence schema version, and counters from different worlds are not comparable.
 */
data class RailwayStateStamp(
    val worldId: String,
    val physicsEpoch: Long,
    val topologyRevision: Long
) {
    init {
        require(worldId.isNotBlank()) { "worldId must not be blank" }
        require(physicsEpoch >= 0L) { "physicsEpoch must be non-negative" }
        require(topologyRevision >= 0L) { "topologyRevision must be non-negative" }
    }
}
