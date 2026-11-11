package work.art1st.javarailways.bukkit.api.observation;

/** Idempotent registration handle for one observation listener. */
@FunctionalInterface
public interface RailwayObservationSubscription extends AutoCloseable {
    @Override
    void close();
}
