package work.art1st.javarailways.bukkit.api.model;

/** One owner/world-bound ordinary model. Calls may originate on any thread and never await rendering. */
public interface ModelHandle extends AutoCloseable {
    /**
     * Submit complete state. Invalid scenes throw before replacing the last valid state.
     * Older/equal revisions and submissions after close are ignored. Rendering failure is logged by the host.
     */
    void submit(ModelPresentation presentation);

    /** Idempotently discard pending display state and arrange target-owned cleanup. */
    @Override
    void close();
}
