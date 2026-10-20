package work.art1st.javarailways.bukkit.api.simulation;

import work.art1st.javarailways.simpleato.api.simulation.PreparedSimulationFeature;
import java.util.UUID;

/** Captures detached domain definitions while the source world's owner and frame boundary are held. */
@FunctionalInterface
public interface SimulationFeaturePreparer {
    /**
     * Returns reusable preparation values, never live callback/runtime instances. Each later target
     * installation must create its own mutable state and close only that target's resources.
     * This callback may read the source's domain values but must not access Bukkit effects or wait
     * for work that requires the same source-world boundary.
     */
    PreparedSimulationFeature prepare(UUID sourceWorldId);
}
