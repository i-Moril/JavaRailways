package work.art1st.javarailways.bukkit.api.rail;

import org.bukkit.entity.Player;
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Selects rails and queries immutable geometry without exposing the host world registry or spatial index.
 */
public interface RailTargetService {
    /**
     * Selects a rail from the player's eye ray, copied before this method returns.
     *
     * <p>Call from the owning thread/region of {@code player}. Completion may happen on any thread;
     * schedule Bukkit work back to the appropriate entity or region scheduler. Returned geometry may
     * be sampled concurrently, even after the rail is removed.</p>
     */
    CompletionStage<Optional<RailTarget>> findLookedAtRail(
        Player player,
        double maxDistance,
        double hitRadius
    );

    /**
     * Finds the canonical immutable A-to-B curve by exact world and rail UUIDs.
     * Positions and arc lengths are in metres, measured from the rail's A end; a caller's BA facing
     * does not change that coordinate. This lookup performs no nearest-point search, pathfinding or
     * scan of all rails and exposes neither a live world nor a live rail.
     *
     * <p>May be called off a Bukkit owning thread. The host uses its loaded-world UUID index and Core's
     * world-local thread-safe rail lookup. A retained curve remains readable after rail deletion or
     * world unload, but does not prove that the rail exists after a later load of the same world UUID.
     * A query already in progress at unload may return the old immutable value.</p>
     *
     * @return the curve, or empty if the rail is absent in the currently loaded host world
     * @throws IllegalStateException if the host world is unregistered, unloading, unloaded or stopped
     */
    Optional<PrimeCurve> findCurve(UUID worldId, UUID railId);
}
