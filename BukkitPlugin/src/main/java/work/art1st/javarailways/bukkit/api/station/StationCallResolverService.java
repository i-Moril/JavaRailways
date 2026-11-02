package work.art1st.javarailways.bukkit.api.station;

import org.bukkit.plugin.Plugin;
import work.art1st.javarailways.simpleato.api.station.StationCallResolutionProvider;
import java.util.UUID;

/** Authenticated installation of one custom station-call resolver per live railway world. */
public interface StationCallResolverService {
    /**
     * Installs a resolver owned by an enabled plugin. A second registration is rejected.
     * Call outside railway World callbacks; management enters the target World owner.
     * The returned handle removes only this registration and may be closed repeatedly.
     * Removal leaves resolution unavailable until an explicit new registration, so a missing
     * plugin never silently changes an installed timetable's policy to the default resolver.
     * Providers must be bounded, deterministic, side-effect-free, and must not re-enter this
     * service or access Bukkit state. Simulation requires the provider's detached preparation API.
     */
    AutoCloseable register(Plugin owner, UUID worldId, StationCallResolutionProvider provider);

    /** Retires this exact plugin instance's registrations in all live worlds. */
    void unregisterOwner(Plugin owner);
}
