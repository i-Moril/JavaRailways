package work.art1st.javarailways.bukkit.api.rail;

import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Selects JavaRailways rails without exposing BukkitPlugin, WorldManager, CoreWorld, or the spatial index.
 *
 * <p>The caller must invoke this method from the owning thread/region of {@code player}. The player's
 * eye ray is copied before the method returns. Completion may happen on any thread; callers must schedule
 * Bukkit work back to the appropriate entity or region scheduler. A returned rail has immutable curve
 * geometry and may be sampled concurrently, even if it is subsequently removed from its world.</p>
 */
public interface RailTargetService {
    CompletionStage<Optional<RailTarget>> findLookedAtRail(
        Player player,
        double maxDistance,
        double hitRadius
    );
}
