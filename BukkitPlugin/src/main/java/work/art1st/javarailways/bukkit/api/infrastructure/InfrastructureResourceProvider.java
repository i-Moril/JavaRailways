package work.art1st.javarailways.bukkit.api.infrastructure;

import org.bukkit.plugin.Plugin;
import work.art1st.javarailways.basic.api.route.ResourcePreparationDescriptor;
import work.art1st.javarailways.basic.api.route.RouteAdmissionFailureCode;

import java.util.UUID;

/**
 * Authoritative existence check for one stable infrastructure {@code typeId}.
 *
 * <p>Called synchronously on the lease service caller's thread. Implementations must be
 * thread-safe, deterministic, non-blocking, and must not access Bukkit entity or region state.</p>
 */
public interface InfrastructureResourceProvider {
    boolean exists(UUID worldId, String resourceId);

    /**
     * Checks one route addition requested by an authenticated plugin. Return {@code null} only
     * when both the resource use and optional preparation descriptor are allowed. Otherwise
     * return {@code INVALID_ADDITION} or {@code INVALID_PREPARATION}. This callback is invoked
     * on the simulation owner before any claim mutation and must be quick and side-effect-free.
     * Lease existence alone is never route authorization. Every provider must explicitly
     * decide whether its resources may be added to a route.
     */
    RouteAdmissionFailureCode validateRouteAddition(
        Plugin requester,
        UUID worldId,
        String resourceId,
        ResourcePreparationDescriptor preparation
    );
}
