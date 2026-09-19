package work.art1st.javarailways.bukkit.api.observation;

import org.bukkit.plugin.Plugin;
import work.art1st.javarailways.basic.api.observation.RailwayObservation;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only railway observations published by JavaRailways for independently built add-ons.
 *
 * <p>Snapshots are immutable values. They are removed on world unload and all registrations are
 * removed when the host closes. Add-ons should close their handles during disable; JavaRailways
 * also removes every registration owned by a disabled plugin.</p>
 */
public interface RailwayObservationService {
    /** Returns the latest completed observation, or empty before first publication/after unload. */
    Optional<RailwayObservation> latest(UUID worldId);

    /**
     * Subscribes {@code owner} to future completed observations for one world.
     * See {@link RailwayObservationListener#onObservation(UUID, RailwayObservation)} for threading.
     */
    RailwayObservationSubscription subscribe(
        Plugin owner,
        UUID worldId,
        RailwayObservationListener listener
    );

    /** Idempotently removes all subscriptions owned by {@code owner}. */
    void unregisterOwner(Plugin owner);
}
