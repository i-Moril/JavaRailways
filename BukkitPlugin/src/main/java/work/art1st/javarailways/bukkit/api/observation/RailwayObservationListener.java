package work.art1st.javarailways.bukkit.api.observation;

import work.art1st.javarailways.basic.api.observation.RailwayObservation;

import java.util.UUID;

/** Receives immutable completed-world observations. */
@FunctionalInterface
public interface RailwayObservationListener {
    /**
     * Runs synchronously on JavaRailways' serialized simulation context after the exact physics
     * ticket represented by {@code observation} completes. It never runs on a physics worker.
     * Implementations must return promptly and must not perform Bukkit entity, location, block, or
     * region side effects directly; schedule those through the appropriate platform owner.
     */
    void onObservation(UUID worldId, RailwayObservation observation);
}
