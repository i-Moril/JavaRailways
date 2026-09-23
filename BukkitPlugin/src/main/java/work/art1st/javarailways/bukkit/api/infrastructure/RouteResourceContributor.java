package work.art1st.javarailways.bukkit.api.infrastructure;

import work.art1st.javarailways.basic.api.route.ContributedRouteResource;
import work.art1st.javarailways.basic.api.route.RouteResourceIntent;

import java.util.Collection;
import java.util.UUID;

/**
 * Adds signalling-policy resources to one generic route request without exposing them to ATO.
 *
 * <p>The host invokes each registered contributor once per new or extended admission, before any
 * claim mutation. Implementations must be quick, deterministic and side-effect-free, and should
 * use their own route index rather than scan every resource definition.</p>
 */
@FunctionalInterface
public interface RouteResourceContributor {
    /** Returns only the resources required by this policy for the supplied immutable route intent. */
    Collection<ContributedRouteResource> contribute(UUID worldId, RouteResourceIntent intent);
}
