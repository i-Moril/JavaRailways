package work.art1st.javarailways.bukkit.api.infrastructure;

/**
 * Idempotent lifecycle handle for one authenticated route-resource contributor.
 *
 * <p>Contributor membership is frozen while any route state may request another suffix. Closing
 * this handle therefore fails until those routes have ended.</p>
 */
@FunctionalInterface
public interface RouteResourceContributorRegistration extends AutoCloseable {
    /** @throws IllegalStateException if any railway world still has an active route state */
    @Override
    void close();
}
