package work.art1st.javarailways.bukkit.api.signal;

import org.bukkit.plugin.Plugin;
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceService;
import work.art1st.javarailways.simpleato.api.route.RoutePlanningRestrictionBinding;

import java.util.Collection;
import java.util.UUID;

/**
 * Authenticated owner/world registration for operational route-planning restrictions.
 *
 * <p>Each call replaces one plugin's complete source set in one live railway world. The host
 * captures all installed sources in stable source-ID order and invokes each source once before a
 * route search. Source callbacks must be bounded, side-effect free and use only the supplied
 * immutable topology; they are never invoked during graph expansion. Callbacks run on the
 * serialized railway owner and must not call Bukkit, perform blocking I/O or re-enter this
 * service.</p>
 *
 * <p>Complete owner removal uses
 * {@link InfrastructureResourceService#unregisterOwner(Plugin)}, together with the owner's signal,
 * inspection and infrastructure registrations. World unload discards the binding.</p>
 */
public interface RoutePlanningRestrictionSourceService {
    /**
     * Atomically replaces this plugin's complete route-planning source set for one live world.
     * An empty set is accepted only when this owner retains another signal or inspection source;
     * complete owner removal must use the unified infrastructure-owner operation.
     *
     * @throws IllegalStateException if the plugin, world or service is unavailable
     * @throws IllegalArgumentException if IDs are invalid or collide with another owner
     */
    void replaceRoutePlanningSources(
        Plugin pluginOwner,
        UUID worldId,
        Collection<RoutePlanningRestrictionBinding> sources
    );
}
