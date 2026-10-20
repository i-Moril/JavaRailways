package work.art1st.javarailways.bukkit.api.simulation;

import org.bukkit.plugin.Plugin;

/** Registers the domain preparation capability of independently installed railway plugins. */
public interface SimulationFeatureService {
    /**
     * Registers one preparer for this enabled plugin instance. Its feature ID is the plugin name in
     * lowercase, matching the host's infrastructure/signal owner identity. The returned registration
     * removes only itself; plugin disable also removes that owner's registration. Captured detached
     * preparations already in use belong to their simulations and are not live registrations.
     */
    AutoCloseable register(Plugin owner, SimulationFeaturePreparer preparer);
}
