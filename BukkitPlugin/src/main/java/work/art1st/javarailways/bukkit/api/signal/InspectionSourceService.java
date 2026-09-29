package work.art1st.javarailways.bukkit.api.signal;

import org.bukkit.plugin.Plugin;
import work.art1st.javarailways.simpleato.api.inspection.InspectionSource;

import java.util.Collection;
import java.util.UUID;

/**
 * Authenticated lifecycle surface for route-local signal inspection sources.
 *
 * <p>Plugins register source callbacks, not caller-constructed route bindings. When SimpleATO has
 * selected an exact production route, the host supplies that route to each source's binding
 * provider and atomically installs the returned complete per-train binding. This prevents add-ons
 * from guessing a route from mutable world state.</p>
 *
 * <p>This service shares owner, world-unload and forced-disable lifecycle with
 * {@link SignalEpochSourceService}. Complete owner removal still goes through the infrastructure
 * owner's unified unregister operation.</p>
 *
 * <p>Binding providers and update handlers run synchronously on the railway simulation owner.
 * They must use only the immutable input values, complete promptly, and must not call Bukkit APIs
 * or re-enter this service. World presentation must be published separately.</p>
 */
public interface InspectionSourceService {
    /**
     * Atomically replaces this plugin's complete inspection source set in one exact live world.
     * An invalid source or active-route binding leaves the previous set installed. If an active
     * route cannot be refreshed before another physics epoch, the world fails closed.
     */
    void replaceInspectionSources(
        Plugin pluginOwner,
        UUID worldId,
        Collection<InspectionSource> sources
    );
}
